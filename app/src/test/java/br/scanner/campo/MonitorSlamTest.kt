package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MonitorSlamTest {
    private fun q(x: Float, y: Float, n: Int = 5000, estado: Int = 0) =
        Protocolo.QuadroNuvem(n, 16, 0L, estado, floatArrayOf(x, y, 0f, 0f, 0f, 0f), FloatArray(0))

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
        assertTrue(p.motivo.contains("salto"))
    }

    @Test fun poucosPontosAvisa() {
        val m = novo()
        var t = 1_000L
        for (i in 0 until 10) { m.quadro(q(i * 1f, 0f, n = 6000), t); t += 1000 }
        val s = m.quadro(q(10f, 0f, n = 1200), t)
        assertEquals(MonitorSlam.Nivel.ATENCAO, s.nivel)
        assertTrue(s.motivo.contains("poucos pontos"))
    }

    @Test fun quadrosParadosViramPerdido() {
        val m = novo()
        m.quadro(q(0f, 0f), 1_000L); m.quadro(q(1f, 0f), 2_000L)
        assertEquals(MonitorSlam.Nivel.ATENCAO, m.verificarAtraso(5_000L).nivel)
        assertEquals(MonitorSlam.Nivel.PERDIDO, m.verificarAtraso(9_000L).nivel)
    }

    @Test fun estadoDiferenteAvisa() {
        val m = novo()
        var t = 1_000L
        for (i in 0 until 5) { m.quadro(q(i * 1f, 0f, estado = 1), t); t += 1000 }
        val s = m.quadro(q(5f, 0f, estado = 7), t)
        assertEquals(MonitorSlam.Nivel.ATENCAO, s.nivel)
        assertTrue(s.motivo.contains("estado 7"))
    }
}
