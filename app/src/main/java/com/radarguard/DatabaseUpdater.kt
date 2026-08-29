package com.radarguard

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Baixa uma base de radares nova.
 *
 * Grava num arquivo temporario e so promove pro definitivo depois de conseguir
 * ler o conteudo. Assim uma queda de rede no meio do download nao deixa o app
 * sem base — no pior caso ele continua com a que veio no APK.
 */
object DatabaseUpdater {

    class Result(val ok: Boolean, val message: String, val count: Int = 0)

    fun download(context: Context, urlText: String): Result {
        if (urlText.isBlank()) {
            return Result(false, "Configure a URL da base antes de atualizar.")
        }
        val url = try {
            URL(urlText)
        } catch (e: Exception) {
            return Result(false, "URL invalida.")
        }
        if (url.protocol != "https") {
            return Result(false, "Use uma URL https.")
        }

        val tmp = File(context.filesDir, "radares.tmp.gz")
        try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "RadarGuard/1.0")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 32 * 1024) }
            }
            if (conn.responseCode !in 200..299) {
                tmp.delete()
                return Result(false, "Servidor respondeu ${conn.responseCode}.")
            }
        } catch (e: Exception) {
            tmp.delete()
            return Result(false, "Falha no download: ${e.javaClass.simpleName}")
        }

        // Valida antes de promover: arquivo truncado ou pagina de erro nao vira base.
        val count = try {
            tmp.inputStream().use { RadarDatabase.parse(it).size }
        } catch (e: Exception) {
            tmp.delete()
            return Result(false, "Arquivo baixado nao e uma base valida.")
        }
        if (count < 100) {
            tmp.delete()
            return Result(false, "Base baixada tem so $count radares; ignorada.")
        }

        val dest = File(context.filesDir, RadarDatabase.UPDATE_FILE)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            return Result(false, "Nao foi possivel salvar a base.")
        }
        return Result(true, "Base atualizada: $count radares.", count)
    }
}
