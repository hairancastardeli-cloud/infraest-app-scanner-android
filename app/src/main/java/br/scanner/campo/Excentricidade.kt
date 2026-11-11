package br.scanner.campo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Afastamento horizontal da antena em relação à ponta do suporte, no referencial do SCANNER (o celular preso nele dá o
 * rumo). Com ele, uma leitura só basta: ponta = antena − afastamento girado pelo rumo do momento.
 *
 * Calibração por uma leitura dupla (o scanner girado 180° em volta da ponta): o meio das leituras é a ponta; o vetor
 * ponta → antena de cada leitura, menos o rumo do celular naquele instante, dá o ângulo do afastamento em relação ao
 * celular. Erros FIXOS do rumo (celular torto no suporte, declinação, metal do scanner) entram igual na calibração e na
 * medida, e se cancelam. A diferença entre os ângulos das duas leituras mede quanto o rumo erra ao girar (coerência).
 */
object Excentricidade {
    data class Calibracao(
        val distancia: Double,          // m
        val angulo: Double,             // graus: direção do afastamento − rumo do celular
        val eixo: String,               // eixo do celular usado ("topo"/"costas")
        val coerencia: Double,          // graus: |diferença| entre os ângulos das duas leituras
        val trajetoria: Double?,        // diferença trajetória − celular na hora da calibração (se havia caminhada)
        val quandoMs: Long,
    ) {
        fun texto(): String = listOf(distancia, angulo, eixo, coerencia, trajetoria ?: Double.NaN, quandoMs).joinToString(";")

        companion object {
            fun de(t: String?): Calibracao? {
                val c = t?.split(';') ?: return null
                if (c.size < 6) return null
                return Calibracao(c[0].toDoubleOrNull() ?: return null, c[1].toDoubleOrNull() ?: return null, c[2],
                    c[3].toDoubleOrNull() ?: 0.0, c[4].toDoubleOrNull()?.takeIf { !it.isNaN() }, c[5].toLongOrNull() ?: 0L)
            }
        }
    }

    fun azimute(dE: Double, dN: Double): Double = RumoCelular.normalizar(Math.toDegrees(atan2(dE, dN)))

    /** Calibração a partir das duas leituras e do rumo médio do celular em cada uma. Null sem rumo. */
    fun calibrar(l1: PontoGnss.Leitura, l2: PontoGnss.Leitura, eixo: String, trajetoria: Double?, agoraMs: Long = System.currentTimeMillis()): Calibracao? {
        val h1 = l1.rumo ?: return null
        val h2 = l2.rumo ?: return null
        val me = (l1.e + l2.e) / 2; val mn = (l1.n + l2.n) / 2
        val a1 = RumoCelular.diferenca(azimute(l1.e - me, l1.n - mn), h1)
        val a2 = RumoCelular.diferenca(azimute(l2.e - me, l2.n - mn), h2)
        val (ang, _) = RumoCelular.media(listOf(a1, a2))
        return Calibracao(hypot(l1.e - l2.e, l1.n - l2.n) / 2, ang, eixo, abs(RumoCelular.diferenca(a1, a2)), trajetoria, agoraMs)
    }

    /** Ponta a partir da posição da antena (E, N) e do rumo da quadrícula do celular. */
    fun ponta(e: Double, n: Double, rumo: Double, c: Calibracao): Pair<Double, Double> {
        val az = Math.toRadians(rumo + c.angulo)
        return (e - c.distancia * sin(az)) to (n - c.distancia * cos(az))
    }
}

/**
 * Trajetória da antena (RTK fixo) enquanto se anda entre os pontos: compara a direção do deslocamento com o rumo do
 * celular. Andando com o scanner apontado para a frente, essa diferença é quase constante; se ela mudar em relação à da
 * calibração, a bússola desviou (portão, carro, armadura) e o rumo pode ser corrigido por essa mudança.
 */
class Trajetoria(private val janelaMs: Long = 120_000) {
    private class Ponto(val e: Double, val n: Double, val ms: Long, val rumo: Double)
    private val pontos = ArrayDeque<Ponto>()
    private val difs = ArrayDeque<Pair<Long, Double>>()
    var andado = 0.0
        private set

    @Synchronized fun adicionar(e: Double, n: Double, ms: Long, rumo: Double) {
        pontos.addLast(Ponto(e, n, ms, rumo))
        while (pontos.isNotEmpty() && ms - pontos.first().ms > 4000) pontos.removeFirst()
        val a = pontos.first()
        val dt = (ms - a.ms) / 1000.0
        val d = hypot(e - a.e, n - a.n)
        if (dt >= 2.0 && d >= 1.2 && d / dt in 0.4..2.5 && !rumo.isNaN() && !a.rumo.isNaN()) {
            val (rm, dp) = RumoCelular.media(pontos.map { it.rumo }.filter { !it.isNaN() })
            if (dp < 8) {                                                    // virando o scanner no meio do trecho: não serve
                difs.addLast(ms to RumoCelular.diferenca(Excentricidade.azimute(e - a.e, n - a.n), rm))
                andado += d / pontos.size
            }
        }
        while (difs.isNotEmpty() && ms - difs.first().first > janelaMs) difs.removeFirst()
    }

    /** (diferença média trajetória − celular, desvio, nº de amostras) dos últimos 2 min; null com pouca caminhada. */
    @Synchronized fun estimativa(): Triple<Double, Double, Int>? {
        if (difs.size < 5) return null
        val (m, dp) = RumoCelular.media(difs.map { it.second })
        return Triple(RumoCelular.diferenca(m, 0.0), dp, difs.size)            // com sinal: −180 a 180
    }
}
