package com.radarguard

/**
 * Decide o que anunciar a cada posicao do GPS.
 *
 * Regras que definem o comportamento:
 *
 *  - Cada radar tem seus proprios estagios (2 km, 1 km, 500 m...) e cada estagio
 *    dispara UMA vez por aproximacao. E o que o Waze nao faz.
 *  - So alerta radar que esta a frente NO SEU SENTIDO. A tag "direction" do OSM
 *    so e aproveitavel em ~10% dos radares (metade dos que a trazem usa
 *    forward/backward, que depende da geometria da via), entao o filtro principal
 *    e geometrico: comparar o rumo ate o radar com o rumo em que voce esta indo.
 *  - Uma vez engatado, o radar continua engatado ate voce passar por ele. Isso e
 *    proposital: a menos de 100 m o rumo calculado ate o radar oscila muito e um
 *    teste de cone puro perderia justamente os alertas finais.
 *  - Se voce estiver acima do limite perto do radar, avisa de novo — que e o
 *    unico alerta que de fato evita a multa.
 *  - SO O RADAR MAIS PROXIMO FALA. Num corredor urbano denso ha mais de 20 radares
 *    dentro do alcance e do cone ao mesmo tempo; se todos escalassem em paralelo
 *    seriam mais de cem avisos no mesmo trecho. Os demais consomem seus estagios
 *    em silencio e assumem a voz quando chega a vez deles.
 */
class AlertEngine(private val db: RadarDatabase, initialConfig: Config = Config()) {

    /** Trocado pela tela de ajustes enquanto o servico le no thread do GPS. */
    @Volatile
    var config: Config = initialConfig

    data class Config(
        /** Distancias de aviso em metros. Ordenadas da maior para a menor no init. */
        val stages: IntArray = intArrayOf(2000, 1000, 500, 300, 200, 100),
        /** Acima disso fala; abaixo, so bipa (nao ha tempo pra frase). */
        val voiceAboveMeters: Int = 300,
        /** Meia-abertura do cone frontal usado pra engatar um radar. */
        val coneDegrees: Double = 30.0,
        /** Abaixo disso nao alerta: parado, a pe ou em congestionamento. */
        val minSpeedKmh: Double = 20.0,
        /** Margem antes de acusar excesso (o GPS le um pouco abaixo do velocimetro). */
        val overspeedToleranceKmh: Int = 3,
        /** Distancia dentro da qual o aviso de excesso passa a valer. */
        val overspeedWithinMeters: Int = 400,
        val overspeedEnabled: Boolean = true
    ) {
        val sortedStages: IntArray = stages.sortedDescending().toIntArray()
        val maxStage: Int get() = if (sortedStages.isEmpty()) 0 else sortedStages[0]
    }

    enum class Kind { STAGE, OVERSPEED }

    data class Alert(
        val kind: Kind,
        val cameraIndex: Int,
        /** Estagio que disparou, em metros. 0 para alerta de excesso. */
        val stageMeters: Int,
        val distanceMeters: Double,
        val limitKmh: Int,
        val speedKmh: Double,
        val label: String
    ) {
        /** Estagios longos merecem fala; os curtos so bipe. */
        fun useVoice(cfg: Config): Boolean =
            kind == Kind.OVERSPEED || stageMeters >= cfg.voiceAboveMeters
    }

    data class Target(
        val cameraIndex: Int,
        val distanceMeters: Double,
        val limitKmh: Int,
        val label: String,
        val engaged: Boolean
    )

    data class Result(val nearest: Target?, val alerts: List<Alert>)

    /** Estado de uma aproximacao em andamento. */
    private class Approach {
        var firedMask = 0
        var minDistance = Double.MAX_VALUE
        var confirmations = 0
        var lastOverspeedMs = 0L
        var lastTick = 0L
    }

    private val states = HashMap<Int, Approach>()
    private val candidates = IntArray(512)

    // Buffers reaproveitados entre as duas passadas: alocar a cada posicao do GPS
    // geraria lixo de sobra num loop que roda 1x por segundo a viagem inteira.
    private val trackIdx = IntArray(512)
    private val trackDist = DoubleArray(512)

    private var tick = 0L
    private var lastAlertMs = 0L

    /**
     * Marca os estagios ja vencidos e devolve o mais apertado deles (-1 se nenhum).
     * Se o GPS engasgar e pular de 2100 m pra 900 m, devolve 1000 — nao 2000.
     */
    private fun consumeStages(st: Approach, d: Double, cfg: Config): Int {
        var toFire = -1
        for (s in cfg.sortedStages.indices) {
            val stage = cfg.sortedStages[s]
            val bit = 1 shl s
            if (d <= stage && (st.firedMask and bit) == 0) {
                st.firedMask = st.firedMask or bit
                toFire = stage   // stages em ordem decrescente: sobra o menor
            }
        }
        return toFire
    }

    /** Ultimo rumo confiavel. O GPS so devolve rumo util com o carro andando. */
    private var lastBearing = Double.NaN
    private var slowSinceMs = 0L

    fun reset() {
        states.clear()
        lastBearing = Double.NaN
        slowSinceMs = 0L
        lastAlertMs = 0L
    }

    fun update(
        lat: Double,
        lon: Double,
        speedMps: Double,
        bearingDeg: Double,
        hasBearing: Boolean,
        nowMs: Long
    ): Result {
        val cfg = config
        val speedKmh = speedMps * 3.6
        tick++

        if (hasBearing && speedKmh >= 10.0) lastBearing = bearingDeg

        // Parado ou muito devagar: nao alerta, mas segura o estado por um tempo pra
        // nao repetir tudo a cada arrancada no transito.
        val tooSlow = speedKmh < cfg.minSpeedKmh
        if (tooSlow) {
            if (slowSinceMs == 0L) slowSinceMs = nowMs
            if (nowMs - slowSinceMs > 90_000L) states.clear()
        } else {
            slowSinceMs = 0L
        }

        if (lastBearing.isNaN()) return Result(null, emptyList())
        val heading = lastBearing

        val range = (cfg.maxStage + 600).toDouble()
        val n = db.near(lat, lon, range, candidates)

        val alerts = ArrayList<Alert>(2)
        var nearest: Target? = null
        var tracked = 0
        var targetSlot = -1
        var targetDist = Double.MAX_VALUE

        for (c in 0 until n) {
            val i = candidates[c]
            val d = GeoMath.distance(lat, lon, db.lat(i), db.lon(i))
            val brg = GeoMath.bearing(lat, lon, db.lat(i), db.lon(i))
            val rel = GeoMath.angleDiff(brg, heading)

            // Quando o OSM informa o sentido medido, descarta o radar da pista contraria.
            val camDir = db.direction(i)
            if (camDir >= 0 && GeoMath.angleDiff(heading, camDir.toDouble()) > 65.0) {
                states.remove(i)
                continue
            }

            var st = states[i]
            if (st == null) {
                if (rel > cfg.coneDegrees) continue   // nao esta a frente: ignora
                st = Approach()
                states[i] = st
            }
            st.lastTick = tick
            st.confirmations++
            if (d < st.minDistance) st.minDistance = d

            // Ja passou pelo radar (afastou do ponto de maior aproximacao) ou virou.
            if (d > st.minDistance + 150.0) {
                states.remove(i)
                continue
            }
            // Ficou claramente pra tras — pega curvas e retornos mais rapido.
            if (st.confirmations >= 2 && rel > 120.0 && d > 120.0) {
                states.remove(i)
                continue
            }

            val limit = db.limitKmh(i)
            val engaged = st.confirmations >= 2
            if (nearest == null || d < nearest.distanceMeters) {
                nearest = Target(i, d, limit, db.label(i), engaged)
            }

            // Duas leituras antes de falar: uma posicao solta do GPS nao dispara alerta.
            if (!engaged) continue

            trackIdx[tracked] = i
            trackDist[tracked] = d
            if (d < targetDist) {
                targetDist = d
                targetSlot = tracked
            }
            tracked++
            if (tracked == trackIdx.size) break
        }

        // Segunda passada: SO o radar mais proximo fala.
        //
        // Num corredor urbano denso cabem mais de 20 radares dentro do alcance de
        // 2,6 km e dentro do cone. Deixar todos escalarem em paralelo daria mais de
        // cem avisos no mesmo trecho — inutilizavel. Os que nao sao o alvo consomem
        // seus estagios em silencio e assumem a voz quando viram a proxima vez.
        if (!tooSlow) {
            for (t in 0 until tracked) {
                val i = trackIdx[t]
                val st = states[i] ?: continue
                val d = trackDist[t]

                if (t != targetSlot) {
                    consumeStages(st, d, cfg)
                    continue
                }

                // Espaco minimo entre falas: dois radares em sequencia nao podem
                // atropelar um ao outro. Sem consumir, o estagio sai no ciclo seguinte.
                if (nowMs - lastAlertMs < MIN_ALERT_GAP_MS) continue

                val limit = db.limitKmh(i)
                val toFire = consumeStages(st, d, cfg)
                if (toFire > 0) {
                    lastAlertMs = nowMs
                    alerts.add(Alert(Kind.STAGE, i, toFire, d, limit, speedKmh, db.label(i)))
                }

                if (cfg.overspeedEnabled && limit > 0 &&
                    d <= cfg.overspeedWithinMeters &&
                    speedKmh > limit + cfg.overspeedToleranceKmh &&
                    nowMs - st.lastOverspeedMs > 4_000L
                ) {
                    st.lastOverspeedMs = nowMs
                    lastAlertMs = nowMs
                    alerts.add(Alert(Kind.OVERSPEED, i, 0, d, limit, speedKmh, db.label(i)))
                }
            }
        }

        // Descarta estados de radares que sairam do alcance. Tem que ser todo ciclo:
        // um estado velho guarda os estagios ja disparados, e se ele sobrevivesse o
        // radar ficaria mudo quando voce passasse por ele de novo na volta.
        val stale = states.entries.iterator()
        while (stale.hasNext()) if (stale.next().value.lastTick != tick) stale.remove()

        // Excesso primeiro, depois o mais proximo: se so der tempo de um, que seja o util.
        alerts.sortWith(
            compareBy<Alert> { if (it.kind == Kind.OVERSPEED) 0 else 1 }
                .thenBy { it.distanceMeters }
        )
        return Result(nearest, alerts)
    }

    private companion object {
        /** Espaco minimo entre dois avisos falados, para nao se atropelarem. */
        const val MIN_ALERT_GAP_MS = 2_500L
    }
}
