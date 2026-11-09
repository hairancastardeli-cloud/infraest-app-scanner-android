package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin

class MonitorSlamTest {
    private fun q(x: Float, y: Float, n: Int = 5000, estado: Int = 0, z: Float = 0f) =
        Protocolo.QuadroNuvem(n, 16, 0L, estado, floatArrayOf(x, y, z, 0f, 0f, 0f), FloatArray(0))

    private fun novo(): MonitorSlam {
        val m = MonitorSlam(Files.createTempDirectory("slam").toFile())
        m.comecar("teste")
        return m
    }

    @Test fun caminhadaNormalFicaOk() {
        val m = novo()
        var t = 1_000L
        for (i in 0 until 30) { val s = m.quadro(q(i * 1.2f, 0f), t); t += 1000; assertEquals(MonitorSlam.Nivel.OK, s.nivel) }
        m.parar()
        val linhas = File(m.arquivoLog!!.path).readLines()
        assertEquals(31, linhas.size)                                // cabeçalho + 30 quadros no CSV
        assertEquals(30, m.amostras.size)
    }

    @Test fun saltoGrandeEhPerdido() {
        // como no BAC: 0,02 → 0,7 → 2,8 → 24,4 m por segundo
        val m = novo()
        var t = 1_000L; var x = 0f
        for (i in 0 until 10) { m.quadro(q(x, 0f), t); x += 0.8f; t += 1000 }
        x += 2.8f; val a = m.quadro(q(x, 0f), t); t += 1000
        assertEquals(MonitorSlam.Nivel.ATENCAO, a.nivel)
        x += 24.4f; val p = m.quadro(q(x, 0f), t)
        assertEquals(MonitorSlam.Nivel.PERDIDO, p.nivel)
        assertTrue(p.perdidoPose)
        assertTrue(p.motivo.contains("salto"))
    }

    @Test fun ambientePobreComparaComONormalDoLevantamento() {
        val m = novo()
        var t = 1_000L; var x = 0f
        for (i in 0 until 40) { m.quadro(q(x, 0f, n = if (i % 2 == 0) 2000 else 1000), t); x += 0.3f; t += 500 }
        assertNotNull(m.pontosNormais)
        // queda gradual até 10%: o alarme antigo (média recente) não pegava; este pega depois de 6 s abaixo de 25%
        var s = m.situacao
        var n = 1500
        for (i in 0 until 40) { n = maxOf(150, (n * 0.93).toInt()); s = m.quadro(q(x, 0f, n = n), t); x += 0.3f; t += 500 }
        assertEquals(MonitorSlam.Nivel.ATENCAO, s.nivel)
        assertTrue(s.motivo.contains("ambiente pobre"))
    }

    @Test fun tremorAvisaAntesDoSalto() {
        val m = novo()
        var t = 1_000L; var x = 0f
        for (i in 0 until 20) { m.quadro(q(x, 0f), t); x += 0.25f; t += 500 }
        var s = m.situacao
        for (i in 0 until 8) { s = m.quadro(q(x + if (i % 2 == 0) 0.4f else -0.4f, 0f), t); x += 0.25f; t += 500 }   // vai e volta 40 cm
        assertEquals(MonitorSlam.Nivel.ATENCAO, s.nivel)
        assertTrue(s.repetir)
        assertTrue(s.motivo.contains("tremendo"))
    }

    @Test fun quadrosParadosViramPerdidoSemPausar() {
        val m = novo()
        m.quadro(q(0f, 0f), 1_000L); m.quadro(q(1f, 0f), 2_000L)
        assertEquals(MonitorSlam.Nivel.ATENCAO, m.verificarAtraso(5_000L).nivel)
        val p = m.verificarAtraso(9_000L)
        assertEquals(MonitorSlam.Nivel.PERDIDO, p.nivel)
        assertFalse(p.perdidoPose)                                   // falta de quadro pode ser só o Wi-Fi
    }

    @Test fun estadoDiferenteAvisa() {
        val m = novo()
        var t = 1_000L
        for (i in 0 until 5) { m.quadro(q(i * 1f, 0f, estado = 1), t); t += 1000 }
        val s = m.quadro(q(5f, 0f, estado = 7), t)
        assertEquals(MonitorSlam.Nivel.ATENCAO, s.nivel)
        assertTrue(s.motivo.contains("estado 7"))
    }

    @Test fun pontoRetomadaFicaAntesDoProblema() {
        val m = novo()
        var t = 1_000L; var x = 0f
        for (i in 0 until 60) { m.quadro(q(x, 0f), t); x += 0.25f; t += 500 }        // 15 m bons
        for (i in 0 until 8) { m.quadro(q(x + if (i % 2 == 0) 0.5f else -0.5f, 0f), t); x += 0.25f; t += 500 }
        m.quadro(q(x + 4f, 0f), t)                                                     // salto
        val r = m.pontoRetomada()!!
        val a = m.amostras
        assertTrue(a[r.first].x < 15.0 - 0.25 * 9)                                     // uns 8 s antes do primeiro sinal
        assertTrue(r.second in 3.0..7.0)
    }

    /** Os CSVs reais de 15/10/2026 (fora do git: dados de campo). Roda só se a pasta existir: -DcsvSlam=<pasta>. */
    @Test fun testesReaisDe15Out() {
        val dir = File(System.getProperty("csvSlam") ?: System.getenv("CSV_SLAM") ?: "")
        assumeTrue(dir.isDirectory)
        fun rodar(f: File): List<MonitorSlam.Situacao> {
            val m = novo()
            return f.readLines().drop(1).filter { it.isNotBlank() }.map { l ->
                val c = l.split(';')
                m.quadro(q(c[4].toFloat(), c[5].toFloat(), c[3].toInt(), c[2].toInt(), c[6].toFloat()), c[0].toLong())
            }
        }
        val t1 = dir.listFiles()!!.first { it.name.contains("slam_teste_2026") }
        val t2 = dir.listFiles()!!.first { it.name.contains("113312") }
        val s1 = rodar(t1)
        assertTrue(s1.none { it.nivel == MonitorSlam.Nivel.PERDIDO })
        assertTrue(s1.none { it.motivo.contains("tremendo") })                         // teste 1: nunca tremeu
        val s2 = rodar(t2)
        val perdido = s2.indexOfFirst { it.perdidoPose }
        val tremor = s2.indexOfFirst { it.motivo.contains("tremendo") }
        assertTrue(perdido > 0 && tremor in 0 until perdido - 8)                       // teste 2: avisou > 4 s antes
        println("teste2: tremor no quadro $tremor, perdido no $perdido; pobre a partir de " +
            s2.indexOfFirst { it.motivo.contains("ambiente pobre") })
    }
}

class SemaforoTest {
    private fun am(ms: Long, x: Double, y: Double, e: Double = Double.NaN, n: Double = Double.NaN, perdido: Boolean = false) =
        MonitorSlam.Amostra(ms, x, y, 0.0, 1000, false, false, perdido, false, e, n)

    @Test fun voltaFechadaEhVerde() {
        val a = ArrayList<MonitorSlam.Amostra>()
        for (i in 0..200) { val th = 2 * Math.PI * i / 200; a.add(am(i * 500L, 5 * cos(th) - 5 + if (i == 200) 0.06 else 0.0, 5 * sin(th))) }
        val r = Semaforo.avaliar(Semaforo.Parte("t", a))
        assertEquals(Semaforo.Cor.VERDE, r.cor)
        assertTrue(r.itens.any { it.texto.contains("6 cm") })
    }

    @Test fun perdidoEhVermelho() {
        val a = (0..50).map { am(it * 500L, it * 0.3, 0.0, perdido = it == 40) }
        assertEquals(Semaforo.Cor.VERMELHO, Semaforo.avaliar(Semaforo.Parte("t", a)).cor)
    }

    @Test fun gnssSlamGiradoBate() {
        // SLAM girado 30° e deslocado em relação ao GNSS: a sobra tem que ser ~0
        val th = Math.toRadians(30.0)
        val a = (0..80).map { i -> val x = i * 0.3; val y = 0.02 * i * i % 7
            am(i * 500L, x, y, 100 + cos(th) * x - sin(th) * y, 50 + sin(th) * x + cos(th) * y) }
        val (rms, max, n) = Semaforo.gnssSlam(a)!!
        assertEquals(81, n); assertTrue(rms < 1e-6 && max < 1e-6)
        // um trecho torto de 3 m vira vermelho
        val b = a.mapIndexed { i, s -> if (i > 60) am(s.ms, s.x, s.y + 3.0, s.e, s.n) else s }
        assertEquals(Semaforo.Cor.VERMELHO, Semaforo.avaliar(Semaforo.Parte("t", b)).cor)
    }
}
