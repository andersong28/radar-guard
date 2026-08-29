package com.radarguard

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Base de radares carregada inteira na memoria.
 *
 * A base do Brasil tem dezenas de milhares de pontos, o que da menos de 1 MB em
 * arrays primitivos. Manter tudo residente evita I/O em disco durante a direcao
 * e dispensa banco e indice espacial. Guardamos arrays paralelos em vez de uma
 * lista de objetos para nao gerar lixo a cada varredura.
 *
 * As linhas vem ordenadas por latitude, entao a busca por proximidade faz uma
 * busca binaria para achar a faixa de latitude e so testa esses candidatos.
 */
class RadarDatabase private constructor(
    private val lat: DoubleArray,
    private val lon: DoubleArray,
    private val limitKmh: IntArray,
    private val direction: IntArray,
    private val label: Array<String>
) {

    val size: Int get() = lat.size

    fun lat(i: Int): Double = lat[i]
    fun lon(i: Int): Double = lon[i]

    /** Limite em km/h, ou 0 se o OSM nao informa. */
    fun limitKmh(i: Int): Int = limitKmh[i]

    /** Sentido medido pelo radar em graus, ou -1 se desconhecido (a maioria). */
    fun direction(i: Int): Int = direction[i]

    fun label(i: Int): String = label[i]

    /**
     * Preenche [out] com os indices dos radares dentro de [radiusMeters] e devolve
     * quantos foram encontrados (no maximo out.size).
     */
    fun near(atLat: Double, atLon: Double, radiusMeters: Double, out: IntArray): Int {
        if (lat.isEmpty()) return 0

        val dLat = radiusMeters / GeoMath.METERS_PER_DEG_LAT
        // Perto dos polos metersPerDegLon tende a zero; o coerceAtLeast evita divisao
        // explosiva. Irrelevante no Brasil, mas a base pode ser regerada pra outro pais.
        val dLon = radiusMeters / GeoMath.metersPerDegLon(atLat).coerceAtLeast(1.0)

        var i = lowerBound(atLat - dLat)
        val maxLat = atLat + dLat
        var n = 0
        while (i < lat.size && lat[i] <= maxLat) {
            // filtro barato de caixa antes da raiz quadrada
            val dx = lon[i] - atLon
            if (dx <= dLon && dx >= -dLon) {
                if (GeoMath.distance(atLat, atLon, lat[i], lon[i]) <= radiusMeters) {
                    out[n++] = i
                    if (n == out.size) return n
                }
            }
            i++
        }
        return n
    }

    /** Primeiro indice cuja latitude e >= [value]. */
    private fun lowerBound(value: Double): Int {
        var lo = 0
        var hi = lat.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lat[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {

        // ".bin" e nao ".gz" de proposito: o AAPT descomprime e renomeia assets
        // terminados em .gz durante o build, o que deixaria o APK sem base.
        const val ASSET_NAME = "radares.bin"
        const val UPDATE_FILE = "radares.bin"

        /**
         * Carrega a base atualizada baixada pelo usuario, caindo para a que veio no APK.
         * Se o arquivo baixado estiver corrompido, o APK ainda funciona.
         */
        fun load(context: Context): RadarDatabase {
            val downloaded = File(context.filesDir, UPDATE_FILE)
            if (downloaded.exists() && downloaded.length() > 0) {
                try {
                    return downloaded.inputStream().use { parse(it) }
                } catch (e: Exception) {
                    downloaded.delete()
                }
            }
            return context.assets.open(ASSET_NAME).use { parse(it) }
        }

        /** Le o CSV gzipado no formato lat;lon;maxspeed;direction;label */
        fun parse(input: InputStream): RadarDatabase {
            val lines = ArrayList<String>(40_000)
            BufferedReader(InputStreamReader(GZIPInputStream(input), Charsets.UTF_8)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    if (line.isNotEmpty()) lines.add(line)
                    line = r.readLine()
                }
            }

            val n = lines.size
            val lat = DoubleArray(n)
            val lon = DoubleArray(n)
            val spd = IntArray(n)
            val dir = IntArray(n)
            val lbl = Array(n) { "" }

            var k = 0
            for (line in lines) {
                // indexOf em vez de split: evita alocar 5 strings por linha
                val a = line.indexOf(';')
                if (a < 0) continue
                val b = line.indexOf(';', a + 1)
                if (b < 0) continue
                val c = line.indexOf(';', b + 1)
                if (c < 0) continue
                val d = line.indexOf(';', c + 1)
                if (d < 0) continue

                val la = line.substring(0, a).toDoubleOrNull() ?: continue
                val lo = line.substring(a + 1, b).toDoubleOrNull() ?: continue

                lat[k] = la
                lon[k] = lo
                spd[k] = line.substring(b + 1, c).toIntOrNull() ?: 0
                dir[k] = line.substring(c + 1, d).toIntOrNull() ?: -1
                lbl[k] = line.substring(d + 1)
                k++
            }

            // linhas malformadas deixam sobra no fim dos arrays; corta
            return if (k == n) {
                RadarDatabase(lat, lon, spd, dir, lbl)
            } else {
                RadarDatabase(
                    lat.copyOf(k), lon.copyOf(k), spd.copyOf(k),
                    dir.copyOf(k), Array(k) { lbl[it] }
                )
            }
        }
    }
}
