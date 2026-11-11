package br.scanner.campo

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * IMU do scanner (serial ttyS0, 200 Hz), lida pelo auxiliar com o levantamento parado e resumida a cada 0,1 s:
 * "#IMU ms n gx gy gz ax ay az temp" — giro = soma de giro × 0,005 s no intervalo (unidade bruta × s, escala ainda
 * desconhecida), acel = média em µm/s² (|a| ≈ 9,8 m/s² parado), temp = °C × 100.
 *
 * - Bolha eletrônica: ângulo entre a gravidade agora e a gravidade gravada com o bastão a prumo ("zerar a vertical").
 *   A IMU fica inclinada dentro da cabeça do scanner (~49°), por isso a referência é medida, não suposta.
 * - Giro em torno da vertical: o giroscópio projetado na vertical de referência, integrado. A escala (graus por unidade)
 *   sai comparando com o rumo do celular preso no scanner durante uma volta. Com ela, o rumo do celular é filtrado pelo
 *   giro da IMU (rápido e estável) e a bússola só corrige a deriva devagar.
 */
class ImuScanner {
    class Resumo(val ms: Long, val n: Int, val giro: DoubleArray, val acel: DoubleArray, val tempC: Double)

    companion object {
        fun ler(l: String): Resumo? {
            val c = l.trim().split(' ')
            if (c.size < 10 || c[0] != "#IMU") return null
            return try {
                Resumo(c[1].toLong(), c[2].toInt(), DoubleArray(3) { c[3 + it].toDouble() },
                    DoubleArray(3) { c[6 + it].toDouble() / 1e6 }, c[9].toDouble() / 100.0)
            } catch (_: NumberFormatException) { null }
        }

        fun unitario(v: DoubleArray): DoubleArray { val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return DoubleArray(3) { v[it] / m } }

        fun angulo(a: DoubleArray, b: DoubleArray): Double {
            val ua = unitario(a); val ub = unitario(b)
            return Math.toDegrees(acos((ua[0] * ub[0] + ua[1] * ub[1] + ua[2] * ub[2]).coerceIn(-1.0, 1.0)))
        }
    }

    @Volatile var ultimo: Resumo? = null
        private set
    @Volatile var ultimoMs = 0L
        private set
    /** Gravidade (unitária, no referencial da IMU) com o suporte a prumo; null até zerar. */
    @Volatile var vertical: DoubleArray? = null
    /** Graus de rumo por unidade de giro integrado (com sinal); null até calibrar. */
    @Volatile var escala: Double? = null
    /** Giro acumulado em torno da vertical (unidade bruta × s). */
    @Volatile var giroVertical = 0.0
        private set

    private val acelMedia = ArrayDeque<DoubleArray>()

    @Synchronized fun adicionar(r: Resumo, agoraMs: Long = System.currentTimeMillis()) {
        ultimo = r; ultimoMs = agoraMs
        acelMedia.addLast(r.acel); if (acelMedia.size > 10) acelMedia.removeFirst()          // 1 s para a bolha
        val v = vertical ?: unitario(r.acel)
        // vertical "para cima" = sentido da aceleração medida parado (força que segura contra a gravidade)
        giroVertical += r.giro[0] * v[0] + r.giro[1] * v[1] + r.giro[2] * v[2]
    }

    fun ativa(agoraMs: Long = System.currentTimeMillis()) = agoraMs - ultimoMs < 1500

    /** Gravidade média do último segundo. */
    @Synchronized fun gravidade(): DoubleArray? {
        if (acelMedia.isEmpty()) return null
        return DoubleArray(3) { k -> acelMedia.sumOf { it[k] } / acelMedia.size }
    }

    /** Inclinação (graus) em relação à vertical zerada; null sem referência ou sem IMU. */
    fun inclinacao(): Double? { val v = vertical ?: return null; val g = gravidade() ?: return null; return angulo(g, v) }

    /** Rumo integrado só pelo giro (graus, relativo), null sem escala. */
    fun rumoGiro(): Double? = escala?.let { giroVertical * it }

    fun texto(v: DoubleArray) = v.joinToString(";")
}

/**
 * Rumo filtrado: o giro da IMU do scanner dá as mudanças (rápido, sem o desvio do metal), e o rumo do celular puxa devagar
 * (constante de tempo [tauS]) para não acumular deriva. Sem IMU calibrada, fica igual ao do celular.
 */
class RumoFundido(private val tauS: Double = 8.0) {
    private var rumo = Double.NaN
    private var giroAnterior: Double? = null
    private var msAnterior = 0L

    @Synchronized fun atualizar(celular: Double, giro: Double?, agoraMs: Long): Double {
        if (rumo.isNaN() || celular.isNaN() && giro == null) { rumo = celular; giroAnterior = giro; msAnterior = agoraMs; return rumo }
        val dt = ((agoraMs - msAnterior) / 1000.0).coerceIn(0.0, 2.0)
        val g0 = giroAnterior
        if (giro != null && g0 != null) rumo = RumoCelular.normalizar(rumo + (giro - g0))
        if (!celular.isNaN()) {
            val k = if (giro == null) 1.0 else (dt / tauS).coerceAtMost(1.0)
            rumo = RumoCelular.normalizar(rumo + k * RumoCelular.diferenca(celular, rumo))
        }
        giroAnterior = giro; msAnterior = agoraMs
        return rumo
    }

    fun valor(): Double = rumo
}
