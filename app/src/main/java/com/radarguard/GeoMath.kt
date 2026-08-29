package com.radarguard

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Geometria local em plano tangente.
 *
 * Nas distancias que nos interessam (ate ~3 km) a aproximacao equirretangular
 * erra menos de 0,1% contra a haversine e custa uma fracao do tempo — o que
 * importa porque varremos a base inteira a cada posicao do GPS.
 */
object GeoMath {

    const val METERS_PER_DEG_LAT = 111_320.0

    /** Metros por grau de longitude na latitude dada (encolhe conforme sai do equador). */
    fun metersPerDegLon(latDeg: Double): Double =
        METERS_PER_DEG_LAT * cos(Math.toRadians(latDeg))

    /** Distancia em metros entre dois pontos. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val mLon = metersPerDegLon((lat1 + lat2) * 0.5)
        val dy = (lat2 - lat1) * METERS_PER_DEG_LAT
        val dx = (lon2 - lon1) * mLon
        return sqrt(dx * dx + dy * dy)
    }

    /** Rumo em graus (0 = norte, 90 = leste) do ponto 1 para o ponto 2. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val mLon = metersPerDegLon((lat1 + lat2) * 0.5)
        val dy = (lat2 - lat1) * METERS_PER_DEG_LAT
        val dx = (lon2 - lon1) * mLon
        val deg = Math.toDegrees(atan2(dx, dy))
        return if (deg < 0) deg + 360.0 else deg
    }

    /**
     * Menor diferenca absoluta entre dois angulos, em graus (0..180).
     * Resolve a virada do 359 para o 0, que e onde comparacao ingenua de rumo quebra.
     */
    fun angleDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }
}
