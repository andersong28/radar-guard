package com.radarguard

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var stages: EditText
    private lateinit var voiceAbove: EditText
    private lateinit var tolerance: EditText
    private lateinit var minSpeed: EditText
    private lateinit var cone: EditText
    private lateinit var overspeed: CheckBox
    private lateinit var updateUrl: EditText
    private lateinit var info: TextView

    private var player: AlertPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        stages = findViewById(R.id.stages)
        voiceAbove = findViewById(R.id.voiceAbove)
        tolerance = findViewById(R.id.tolerance)
        minSpeed = findViewById(R.id.minSpeed)
        cone = findViewById(R.id.cone)
        overspeed = findViewById(R.id.overspeed)
        updateUrl = findViewById(R.id.updateUrl)
        info = findViewById(R.id.info)

        stages.setText(prefs.stagesCsv)
        voiceAbove.setText(prefs.voiceAboveMeters.toString())
        tolerance.setText(prefs.overspeedToleranceKmh.toString())
        minSpeed.setText(prefs.minSpeedKmh.toString())
        cone.setText(prefs.coneDegrees.toString())
        overspeed.isChecked = prefs.overspeedEnabled
        updateUrl.setText(prefs.updateUrl)

        findViewById<Button>(R.id.test).setOnClickListener { playSample() }
        findViewById<Button>(R.id.update).setOnClickListener { runUpdate() }

        showInfo("")
    }

    /** Toca a escala inteira pra voce ouvir como cada estagio soa antes de dirigir. */
    private fun playSample() {
        save()
        val p = player ?: AlertPlayer(this).also { player = it }
        val list = prefs.stages()
        val h = Handler(Looper.getMainLooper())
        var delay = 0L
        for (s in list) {
            h.postDelayed({ p.playSample(s, 80) }, delay)
            delay += 2_600L
        }
    }

    private fun runUpdate() {
        save()
        val url = prefs.updateUrl
        Toast.makeText(this, R.string.updating, Toast.LENGTH_SHORT).show()
        Thread {
            val r = DatabaseUpdater.download(this, url)
            if (r.ok) prefs.lastUpdate = java.text.SimpleDateFormat(
                "dd/MM/yyyy HH:mm", java.util.Locale("pt", "BR")
            ).format(java.util.Date())
            runOnUiThread {
                Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                showInfo(r.message)
            }
        }.start()
    }

    private fun showInfo(extra: String) {
        val sb = StringBuilder()
        val last = prefs.lastUpdate
        sb.append(if (last.isBlank()) getString(R.string.info_bundled)
                  else getString(R.string.info_updated, last))
        val count = AppBus.latest.cameraCount
        if (count > 0) sb.append("\n").append(getString(R.string.info_count, count))
        if (extra.isNotBlank()) sb.append("\n").append(extra)
        sb.append("\n\n").append(getString(R.string.info_source))
        info.text = sb.toString()
    }

    private fun save() {
        prefs.stagesCsv = stages.text.toString().ifBlank { Prefs.DEFAULT_STAGES }
        prefs.voiceAboveMeters = voiceAbove.text.toString().toIntOrNull() ?: 300
        prefs.overspeedToleranceKmh = tolerance.text.toString().toIntOrNull() ?: 3
        prefs.minSpeedKmh = minSpeed.text.toString().toIntOrNull() ?: 20
        prefs.coneDegrees = (cone.text.toString().toIntOrNull() ?: 30).coerceIn(10, 90)
        prefs.overspeedEnabled = overspeed.isChecked
        prefs.updateUrl = updateUrl.text.toString().trim()
        // Normaliza o campo caso o usuario tenha digitado algo invalido.
        stages.setText(prefs.stages().joinToString(","))
    }

    override fun onPause() {
        save()
        // O servico so releria os ajustes ao reiniciar; empurra na hora se estiver rodando.
        if (AppBus.latest.running) LocationService.start(this)
        super.onPause()
    }

    override fun onDestroy() {
        player?.shutdown()
        player = null
        super.onDestroy()
    }
}
