package com.radarguard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Toca os alertas.
 *
 * Tudo sai com USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, que e o que faz o Android
 * abaixar o volume da musica/Waze em vez de cortar, e o que roteia o audio pro
 * viva-voz do carro no Bluetooth.
 *
 * Estagios longos falam; os curtos so bipam, porque a 100 km/h nao ha tempo de
 * uma frase terminar antes do radar.
 */
class AlertPlayer(context: Context) {

    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var tts: TextToSpeech? = null

    /** Falso quando o aparelho nao tem voz pt-BR instalada; ai o app so bipa. */
    @Volatile var voiceAvailable = false
        private set

    @Volatile private var ttsReady = false
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var utteranceSeq = 0

    private val releaseFocus = Runnable { abandonFocus() }

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val res = t.setLanguage(Locale("pt", "BR"))
                voiceAvailable = res != TextToSpeech.LANG_MISSING_DATA &&
                        res != TextToSpeech.LANG_NOT_SUPPORTED
                t.setAudioAttributes(attrs)
                t.setSpeechRate(1.05f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = scheduleFocusRelease()
                    override fun onError(utteranceId: String?) = scheduleFocusRelease()
                })
                ttsReady = true
            }
        }
    }

    fun play(alert: AlertEngine.Alert, cfg: AlertEngine.Config) {
        requestFocus()
        if (alert.kind == AlertEngine.Kind.OVERSPEED) {
            beep(URGENT)
            speak(Phrases.overspeed(alert))
            return
        }
        if (alert.useVoice(cfg) && voiceAvailable && ttsReady) {
            beep(patternFor(alert.stageMeters))
            speak(Phrases.stage(alert))
        } else {
            beep(patternFor(alert.stageMeters))
            scheduleFocusRelease()
        }
    }

    /** Teste manual na tela de ajustes. */
    fun playSample(stageMeters: Int, limitKmh: Int) {
        requestFocus()
        beep(patternFor(stageMeters))
        if (voiceAvailable && ttsReady) {
            speak(Phrases.distance(stageMeters) + if (limitKmh > 0) ", limite $limitKmh" else "")
        } else {
            scheduleFocusRelease()
        }
    }

    private fun speak(text: String) {
        val t = tts
        if (t == null || !ttsReady || !voiceAvailable) {
            scheduleFocusRelease()
            return
        }
        // QUEUE_FLUSH: um alerta novo sempre vale mais que o que ainda esta falando.
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rg-${utteranceSeq++}")
    }

    // ---- bipes -------------------------------------------------------------

    /** freqHz, duracaoMs, pausaMs — repetido na sequencia. */
    private class Pattern(val freq: Int, val durMs: Int, val gapMs: Int, val times: Int)

    private val URGENT = Pattern(1500, 90, 60, 5)

    private fun patternFor(stageMeters: Int): Pattern = when {
        stageMeters >= 2000 -> Pattern(700, 140, 0, 1)
        stageMeters >= 1000 -> Pattern(800, 130, 70, 1)
        stageMeters >= 500 -> Pattern(950, 110, 70, 2)
        stageMeters >= 300 -> Pattern(1100, 100, 65, 2)
        stageMeters >= 200 -> Pattern(1250, 95, 60, 3)
        else -> Pattern(1400, 85, 55, 4)
    }

    private fun beep(p: Pattern) {
        val track = buildTrack(p) ?: return
        try {
            track.play()
        } catch (e: IllegalStateException) {
            track.release()
            return
        }
        val totalMs = p.times * (p.durMs + p.gapMs) + 120
        handler.postDelayed({
            try {
                track.stop()
            } catch (e: IllegalStateException) {
                // ja parou sozinho ao chegar no fim do buffer
            }
            track.release()
        }, totalMs.toLong())
    }

    private fun buildTrack(p: Pattern): AudioTrack? {
        val sr = 22_050
        val samplesTone = sr * p.durMs / 1000
        val samplesGap = sr * p.gapMs / 1000
        val pcm = ShortArray((samplesTone + samplesGap) * p.times)

        var w = 0
        repeat(p.times) {
            for (i in 0 until samplesTone) {
                // rampa de 3 ms nas pontas: senoide cortada no zero estala no alto-falante
                val ramp = sr * 3 / 1000
                val env = when {
                    i < ramp -> i.toFloat() / ramp
                    i > samplesTone - ramp -> (samplesTone - i).toFloat() / ramp
                    else -> 1f
                }
                val v = sin(2.0 * PI * p.freq * i / sr) * 0.55 * env
                pcm[w++] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            w += samplesGap   // silencio: ShortArray ja nasce zerado
        }

        return try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { it.write(pcm, 0, pcm.size) }
        } catch (e: Exception) {
            null
        }
    }

    // ---- foco de audio -----------------------------------------------------

    private fun requestFocus() {
        handler.removeCallbacks(releaseFocus)
        if (hasFocus) return
        hasFocus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = req
            audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /** Segura o foco um instante alem do alerta: soltar na hora corta o fim da frase. */
    private fun scheduleFocusRelease() {
        handler.removeCallbacks(releaseFocus)
        handler.postDelayed(releaseFocus, 1_200L)
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
        hasFocus = false
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        abandonFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}

/** Frases em pt-BR, curtas de proposito: ao volante, frase longa e ruido. */
object Phrases {

    fun distance(meters: Int): String = when {
        meters >= 1000 && meters % 1000 == 0 -> {
            val km = meters / 1000
            if (km == 1) "Radar a um quilômetro" else "Radar a $km quilômetros"
        }
        meters >= 1000 -> "Radar a ${"%.1f".format(meters / 1000.0).replace('.', ',')} quilômetros"
        else -> "Radar a $meters metros"
    }

    fun stage(a: AlertEngine.Alert): String {
        val base = distance(a.stageMeters)
        return if (a.limitKmh > 0) "$base, limite ${a.limitKmh}" else base
    }

    fun overspeed(a: AlertEngine.Alert): String =
        if (a.limitKmh > 0) "Reduza. Radar de ${a.limitKmh}." else "Reduza. Radar à frente."
}
