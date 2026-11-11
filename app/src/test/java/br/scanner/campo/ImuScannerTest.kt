package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ImuScannerTest {
    // linha que o auxiliar montou com os quadros reais capturados no scanner (10/11/2026)
    private val real = "#IMU 36920 6 3652.6 -3817.2 -4450.0 6325829 -7258 -7409334 3608"

    @Test fun leOResumoReal() {
        val r = ImuScanner.ler(real)!!
        assertEquals(6, r.n)
        assertEquals(36.08, r.tempC, 1e-9)
        val g = sqrt(r.acel.sumOf { it * it })
        assertEquals(9.74, g, 0.02)                                     // gravidade em m/s²
        assertNull(ImuScanner.ler("#OK"))
        assertNull(ImuScanner.ler("#IMU 1 2 x"))
    }

    private fun linha(ax: Double, ay: Double, az: Double, gz: Double = 0.0) =
        "#IMU 1 20 0.0 0.0 %.1f %d %d %d 3600".format(java.util.Locale.US, gz, (ax * 1e6).toLong(), (ay * 1e6).toLong(), (az * 1e6).toLong())

    @Test fun bolhaMedeAInclinacaoAPartirDaVerticalZerada() {
        val imu = ImuScanner()
        // IMU montada inclinada dentro da cabeça: a vertical é a gravidade medida com o bastão a prumo
        val r0 = ImuScanner.ler(real)!!
        for (k in 0 until 10) imu.adicionar(r0, 1000L + k * 100)
        imu.vertical = ImuScanner.unitario(imu.gravidade()!!)
        assertEquals(0.0, imu.inclinacao()!!, 1e-6)
        // inclina 1° em torno do eixo Y da IMU
        val a = r0.acel; val t = Math.toRadians(1.0)
        val ax = a[0] * cos(t) + a[2] * sin(t); val az = -a[0] * sin(t) + a[2] * cos(t)
        for (k in 0 until 10) imu.adicionar(ImuScanner.ler(linha(ax, a[1], az))!!, 3000L + k * 100)
        assertEquals(1.0, imu.inclinacao()!!, 0.01)
    }

    @Test fun giroEmTornoDaVerticalComEscala() {
        val imu = ImuScanner()
        imu.vertical = doubleArrayOf(0.0, 0.0, 1.0)
        imu.escala = 0.5
        for (k in 0 until 10) imu.adicionar(ImuScanner.ler(linha(0.0, 0.0, 9.8, gz = 4.0))!!, 1000L + k * 100)
        assertEquals(40.0 * 0.5, imu.rumoGiro()!!, 1e-9)
    }

    @Test fun rumoFundidoSegueOGiroEPuxaDevagarParaOCelular() {
        val f = RumoFundido(tauS = 8.0)
        f.atualizar(100.0, 0.0, 0L)
        // o giro diz +30°; a bússola, perturbada, continua em 100°: o fundido vai quase todo com o giro
        val v = f.atualizar(100.0, 30.0, 100L)
        assertEquals(130.0 - 30.0 * 0.1 / 8.0, v, 1e-6)
        // sem giro (IMU fora), segue o celular
        val s = RumoFundido()
        s.atualizar(10.0, null, 0L)
        assertEquals(350.0, s.atualizar(350.0, null, 100L), 1e-9)
    }
}
