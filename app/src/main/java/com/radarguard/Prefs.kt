package com.radarguard

import android.content.Context

/** Ajustes do usuario. SharedPreferences basta: sao poucos campos e leitura rara. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("radarguard", Context.MODE_PRIVATE)

    var stagesCsv: String
        get() = sp.getString(KEY_STAGES, DEFAULT_STAGES) ?: DEFAULT_STAGES
        set(v) = sp.edit().putString(KEY_STAGES, v).apply()

    var voiceAboveMeters: Int
        get() = sp.getInt(KEY_VOICE_ABOVE, 300)
        set(v) = sp.edit().putInt(KEY_VOICE_ABOVE, v).apply()

    var overspeedEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERSPEED, true)
        set(v) = sp.edit().putBoolean(KEY_OVERSPEED, v).apply()

    var overspeedToleranceKmh: Int
        get() = sp.getInt(KEY_TOLERANCE, 3)
        set(v) = sp.edit().putInt(KEY_TOLERANCE, v).apply()

    var minSpeedKmh: Int
        get() = sp.getInt(KEY_MIN_SPEED, 20)
        set(v) = sp.edit().putInt(KEY_MIN_SPEED, v).apply()

    var coneDegrees: Int
        get() = sp.getInt(KEY_CONE, 30)
        set(v) = sp.edit().putInt(KEY_CONE, v).apply()

    var updateUrl: String
        get() = sp.getString(KEY_UPDATE_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_UPDATE_URL, v).apply()

    var lastUpdate: String
        get() = sp.getString(KEY_LAST_UPDATE, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_UPDATE, v).apply()

    /** Converte o CSV de estagios em array, caindo pro padrao se o texto estiver ruim. */
    fun stages(): IntArray {
        val parsed = stagesCsv.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 20..20_000 }
            .distinct()
            .sortedDescending()
        // Mais de 32 estagios estouraria a mascara de bits do AlertEngine.
        return if (parsed.isEmpty()) defaultStages() else parsed.take(32).toIntArray()
    }

    fun toConfig(): AlertEngine.Config = AlertEngine.Config(
        stages = stages(),
        voiceAboveMeters = voiceAboveMeters,
        coneDegrees = coneDegrees.toDouble(),
        minSpeedKmh = minSpeedKmh.toDouble(),
        overspeedToleranceKmh = overspeedToleranceKmh,
        overspeedEnabled = overspeedEnabled
    )

    companion object {
        const val DEFAULT_STAGES = "2000,1000,500,300,200,100"
        private const val KEY_STAGES = "stages"
        private const val KEY_VOICE_ABOVE = "voice_above"
        private const val KEY_OVERSPEED = "overspeed"
        private const val KEY_TOLERANCE = "tolerance"
        private const val KEY_MIN_SPEED = "min_speed"
        private const val KEY_CONE = "cone"
        private const val KEY_UPDATE_URL = "update_url"
        private const val KEY_LAST_UPDATE = "last_update"

        fun defaultStages(): IntArray = intArrayOf(2000, 1000, 500, 300, 200, 100)
    }
}
