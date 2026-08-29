package com.radarguard

import android.os.Handler
import android.os.Looper

/**
 * Canal entre o servico de GPS e a tela.
 *
 * Um objeto com callback resolve; StateFlow ou LiveData trariam coroutines ou
 * androidx.lifecycle so pra isso, e o app inteiro nao tem dependencia externa.
 */
object AppBus {

    data class Status(
        val running: Boolean,
        val hasFix: Boolean,
        val speedKmh: Double,
        val accuracyMeters: Float,
        /** Radar mais proximo a frente, se houver. */
        val targetDistance: Double,
        val targetLimitKmh: Int,
        val targetLabel: String,
        val overLimit: Boolean,
        val cameraCount: Int
    )

    val IDLE = Status(false, false, 0.0, 0f, -1.0, 0, "", false, 0)

    @Volatile
    var latest: Status = IDLE
        private set

    private val main = Handler(Looper.getMainLooper())
    private var listener: ((Status) -> Unit)? = null

    fun observe(l: (Status) -> Unit) {
        listener = l
        l(latest)
    }

    fun stopObserving() {
        listener = null
    }

    fun publish(status: Status) {
        latest = status
        val l = listener ?: return
        main.post { l(status) }
    }
}
