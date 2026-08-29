package com.radarguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var speed: TextView
    private lateinit var radarCard: LinearLayout
    private lateinit var radarTitle: TextView
    private lateinit var radarDistance: TextView
    private lateinit var radarLimit: TextView
    private lateinit var radarLabel: TextView
    private lateinit var toggle: Button

    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Sem isso a tela apaga no meio da viagem e o motorista perde o velocimetro.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = findViewById(R.id.status)
        speed = findViewById(R.id.speed)
        radarCard = findViewById(R.id.radarCard)
        radarTitle = findViewById(R.id.radarTitle)
        radarDistance = findViewById(R.id.radarDistance)
        radarLimit = findViewById(R.id.radarLimit)
        radarLabel = findViewById(R.id.radarLabel)
        toggle = findViewById(R.id.toggle)

        toggle.setOnClickListener {
            if (running) {
                LocationService.stop(this)
                running = false
                render(AppBus.IDLE)
            } else {
                if (ensurePermissions()) startTracking()
            }
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        AppBus.observe { render(it) }
    }

    override fun onStop() {
        AppBus.stopObserving()
        super.onStop()
    }

    private fun startTracking() {
        running = true
        LocationService.start(this)
        status.text = getString(R.string.status_starting)
        toggle.text = getString(R.string.stop)
    }

    private fun ensurePermissions(): Boolean {
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Sem POST_NOTIFICATIONS no Android 13+ o servico em foreground nao mostra nada.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)

        if (needed.isEmpty()) return true
        requestPermissions(needed.toTypedArray(), REQ_PERMS)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode != REQ_PERMS) return
        val locationIdx = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationOk = locationIdx < 0 ||
                grantResults.getOrNull(locationIdx) == PackageManager.PERMISSION_GRANTED
        if (locationOk) {
            startTracking()
        } else {
            status.text = getString(R.string.need_location)
        }
    }

    private fun render(s: AppBus.Status) {
        running = s.running
        toggle.text = getString(if (s.running) R.string.stop else R.string.start)

        status.text = when {
            !s.running -> getString(R.string.status_stopped)
            !s.hasFix -> getString(R.string.status_waiting_gps)
            else -> getString(
                R.string.status_running,
                s.cameraCount,
                s.accuracyMeters.roundToInt()
            )
        }

        speed.text = if (s.running && s.hasFix) s.speedKmh.roundToInt().toString() else "--"

        if (s.targetDistance < 0) {
            radarTitle.text = getString(R.string.no_camera)
            radarDistance.text = "—"
            radarLimit.text = ""
            radarLabel.text = ""
            radarCard.setBackgroundColor(Color.parseColor("#141B24"))
            speed.setTextColor(Color.parseColor("#F2F6FA"))
            return
        }

        radarTitle.text = getString(R.string.next_camera)
        radarDistance.text = formatDistance(s.targetDistance)
        radarLimit.text = if (s.targetLimitKmh > 0) "${s.targetLimitKmh}" else ""
        radarLabel.text = s.targetLabel

        // Vermelho = acima do limite com radar a frente. E o unico estado que exige acao.
        if (s.overLimit) {
            radarCard.setBackgroundColor(Color.parseColor("#7F1D1D"))
            speed.setTextColor(Color.parseColor("#FF6B6B"))
        } else {
            radarCard.setBackgroundColor(Color.parseColor("#141B24"))
            speed.setTextColor(Color.parseColor("#F2F6FA"))
        }
    }

    private fun formatDistance(m: Double): String = when {
        m >= 1000 -> "%.1f km".format(m / 1000).replace('.', ',')
        else -> "${m.roundToInt()} m"
    }

    companion object {
        private const val REQ_PERMS = 42
    }
}
