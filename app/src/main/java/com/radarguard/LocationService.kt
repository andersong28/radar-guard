package com.radarguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlin.math.roundToInt

/**
 * Servico em primeiro plano que le o GPS e dispara os alertas.
 *
 * Roda em foreground porque o Android mata servico comum em segundo plano — e o
 * app precisa continuar alertando com a tela apagada ou com o Waze na frente.
 */
class LocationService : Service(), LocationListener {

    private lateinit var prefs: Prefs
    private var locationManager: LocationManager? = null
    private var player: AlertPlayer? = null
    @Volatile private var engine: AlertEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var cameraCount = 0
    private var lastNotifyMs = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        player = AlertPlayer(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification("Carregando base de radares..."))

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadarGuard::gps").apply {
                setReferenceCounted(false)
                acquire(8 * 60 * 60 * 1000L)   // teto de 8 h: viagem longa sem vazar bateria
            }
        }

        // Ler dezenas de milhares de linhas na main thread trava a UI na abertura.
        if (engine == null) {
            Thread {
                val db = try {
                    RadarDatabase.load(this)
                } catch (e: Exception) {
                    null
                }
                if (db == null) {
                    publish(false, false, 0.0, 0f, null)
                    updateNotification("Erro: base de radares nao carregou")
                    return@Thread
                }
                cameraCount = db.size
                engine = AlertEngine(db, prefs.toConfig())
                startGps()
            }.start()
        } else {
            engine?.config = prefs.toConfig()
            startGps()
        }

        return START_STICKY
    }

    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotification("Sem permissao de localizacao")
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            // 1 Hz e o que o GPS de celular entrega de verdade; pedir mais so gasta bateria.
            // O Looper explicito e obrigatorio: startGps() e chamado da thread que
            // carrega a base, que nao tem Looper proprio. Alem disso entrega os
            // callbacks na main thread, que e onde o TTS e a UI podem ser tocados.
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
            )
            updateNotification("Aguardando sinal de GPS...")
        } catch (e: SecurityException) {
            updateNotification("Sem permissao de localizacao")
        } catch (e: IllegalArgumentException) {
            updateNotification("GPS indisponivel neste aparelho")
        }
    }

    override fun onLocationChanged(location: Location) {
        val eng = engine ?: return
        val speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0

        val result = eng.update(
            location.latitude,
            location.longitude,
            speedMps,
            if (location.hasBearing()) location.bearing.toDouble() else 0.0,
            location.hasBearing(),
            System.currentTimeMillis()
        )

        val cfg = eng.config
        // Um alerta por ciclo: dois avisos juntos viram ruido. O engine ja ordenou
        // por prioridade, entao o primeiro e o que mais importa.
        result.alerts.firstOrNull()?.let { player?.play(it, cfg) }

        val speedKmh = speedMps * 3.6
        val target = result.nearest
        val over = target != null && target.limitKmh > 0 &&
                speedKmh > target.limitKmh + cfg.overspeedToleranceKmh

        publish(true, true, speedKmh, location.accuracy, target, over)

        val now = System.currentTimeMillis()
        if (now - lastNotifyMs > 3_000L) {
            lastNotifyMs = now
            updateNotification(
                if (target != null) {
                    val lim = if (target.limitKmh > 0) " (${target.limitKmh})" else ""
                    "${speedKmh.roundToInt()} km/h  ·  radar em ${fmt(target.distanceMeters)}$lim"
                } else {
                    "${speedKmh.roundToInt()} km/h  ·  nenhum radar a frente"
                }
            )
        }
    }

    private fun fmt(m: Double): String =
        if (m >= 1000) "${"%.1f".format(m / 1000).replace('.', ',')} km" else "${m.roundToInt()} m"

    private fun publish(
        running: Boolean, fix: Boolean, speedKmh: Double, acc: Float,
        target: AlertEngine.Target?, over: Boolean = false
    ) {
        AppBus.publish(
            AppBus.Status(
                running = running,
                hasFix = fix,
                speedKmh = speedKmh,
                accuracyMeters = acc,
                targetDistance = target?.distanceMeters ?: -1.0,
                targetLimitKmh = target?.limitKmh ?: 0,
                targetLabel = target?.label ?: "",
                overLimit = over,
                cameraCount = cameraCount
            )
        )
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        updateNotification("GPS desligado no aparelho")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            locationManager?.removeUpdates(this)
        } catch (e: SecurityException) {
            // permissao revogada enquanto rodava; nada a fazer
        }
        player?.shutdown()
        player = null
        engine?.reset()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        AppBus.publish(AppBus.IDLE)
        super.onDestroy()
    }

    // ---- notificacao -------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID, "Monitoramento de radares", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mostra velocidade e proximo radar enquanto o app roda"
            setShowBadge(false)
            enableVibration(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setContentTitle("RadarGuard ativo")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val ACTION_STOP = "com.radarguard.STOP"
        private const val CHANNEL_ID = "radarguard_gps"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocationService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
