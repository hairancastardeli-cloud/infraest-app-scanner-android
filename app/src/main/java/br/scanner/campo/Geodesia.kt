package br.scanner.campo

import kotlin.math.atan
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Latitude/longitude → UTM no elipsoide GRS80 (SIRGAS 2000, o mesmo do WGS84 no nível de milímetro). Série de Krüger
 * até n⁴ (precisão de milímetro dentro do fuso). As coordenadas saem no referencial da correção RTK: com caster da RBMC
 * ou de rede ajustada ao SIRGAS 2000, é SIRGAS 2000.
 */
object Geodesia {
    private const val A = 6378137.0
    private const val F = 1 / 298.257222101
    private const val K0 = 0.9996

    data class Utm(val e: Double, val n: Double, val fuso: Int, val sul: Boolean) {
        fun nomeFuso() = "$fuso${if (sul) "S" else "N"}"
    }

    fun fuso(lon: Double): Int = (floor((lon + 180) / 6).toInt() + 1).coerceIn(1, 60)

    /** [fusoForcado]: para manter no mesmo fuso todas as épocas de um ponto (ponto em cima da divisa de fusos). */
    fun utm(lat: Double, lon: Double, fusoForcado: Int? = null): Utm {
        val z = fusoForcado ?: fuso(lon)
        val n = F / (2 - F)
        val n2 = n * n; val n3 = n2 * n; val n4 = n3 * n
        val aa = A / (1 + n) * (1 + n2 / 4 + n4 / 64)
        val alfa = doubleArrayOf(
            n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180,
            13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440,
            61 * n3 / 240 - 103 * n4 / 140,
            49561 * n4 / 161280,
        )
        val fi = Math.toRadians(lat)
        val dl = Math.toRadians(lon - (6.0 * z - 183))
        val c = 2 * sqrt(n) / (1 + n)
        val t = sinh(atanh(sin(fi)) - c * atanh(c * sin(fi)))
        val xi = atan(t / cos(dl))
        val eta = atanh(sin(dl) / sqrt(1 + t * t))
        var e = eta; var nn = xi
        for (j in 1..4) {
            e += alfa[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
            nn += alfa[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
        }
        val sul = lat < 0
        return Utm(500000 + K0 * aa * e, (if (sul) 10_000_000.0 else 0.0) + K0 * aa * nn, z, sul)
    }
}
