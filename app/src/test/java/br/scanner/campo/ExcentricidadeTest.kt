package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ExcentricidadeTest {
    // ponta verdadeira; antena a 5 cm, 70° à direita da frente do scanner; o celular lê o rumo com 12° de erro fixo
    private val pE = 333_000.0; private val pN = 7_394_000.0
    private val dist = 0.05; private val angAntena = 70.0; private val erroCelular = 12.0

    private fun leitura(frente: Double, ruido: Double = 0.0): PontoGnss.Leitura {
        val az = Math.toRadians(frente + angAntena)
        val e = pE + dist * sin(az) + ruido; val n = pN + dist * cos(az)
        val ep = List(10) { PontoGnss.Epoca(e, n, 700.0, 705.0, 4, 20, RumoCelular.normalizar(frente + erroCelular)) }
        return PontoGnss.Leitura(ep, 23, true)
    }

    @Test fun calibraELeituraUnicaAcertaAPonta() {
        val cal = Excentricidade.calibrar(leitura(30.0), leitura(210.0), "costas", null)!!
        assertEquals(0.05, cal.distancia, 1e-6)
        assertEquals(angAntena - erroCelular, cal.angulo, 1e-6)        // o erro fixo do celular fica dentro do ângulo
        assertEquals(0.0, cal.coerencia, 1e-6)
        for (frente in listOf(0.0, 145.0, 290.0)) {                    // uma leitura, scanner para qualquer lado
            val l = leitura(frente)
            val (e, n) = Excentricidade.ponta(l.e, l.n, l.rumo!!, cal)
            assertEquals(pE, e, 1e-4); assertEquals(pN, n, 1e-4)
        }
        val r = PontoGnss.Resultado.leituraUnica("P1", "", PontoGnss.Suporte.BASTAO, 2.0, leitura(145.0), cal, leitura(145.0).rumo!!, emptyList())
        assertEquals(pE, r.e, 1e-4); assertEquals("1 leitura + rumo", r.metodo)
        assertTrue(PontoGnss.linhaCsv(r).contains(",1 leitura + rumo,"))
    }

    @Test fun calibracaoGravaELe() {
        val cal = Excentricidade.calibrar(leitura(30.0), leitura(210.0), "topo", 4.5, 123L)!!
        val de = Excentricidade.Calibracao.de(cal.texto())!!
        assertEquals(cal, de)
        assertNull(Excentricidade.Calibracao.de("lixo"))
    }

    @Test fun semRumoNaoCalibra() {
        val sem = PontoGnss.Leitura(List(5) { PontoGnss.Epoca(1.0, 2.0, 3.0, 4.0, 4, 10) }, 23, true)
        assertNull(sem.rumo)
        assertNull(Excentricidade.calibrar(sem, sem, "topo", null))
    }

    @Test fun trajetoriaMedeADiferencaParaOCelular() {
        val t = Trajetoria()
        // andando para o leste a 1 m/s, celular dizendo 95°: trajetória − celular = −5°
        for (s in 0 until 30) t.adicionar(1000.0 + s, 5000.0, 1000L * s, 95.0)
        val est = t.estimativa()
        assertNotNull(est)
        assertEquals(-5.0, est!!.first, 0.01)
        // parado não conta
        val p = Trajetoria()
        for (s in 0 until 30) p.adicionar(1000.0, 5000.0, 1000L * s, 95.0)
        assertNull(p.estimativa())
    }

    @Test fun mediaCircularPassaPeloNorte() {
        val (m, _) = RumoCelular.media(listOf(350.0, 10.0))
        assertEquals(0.0, RumoCelular.diferenca(m, 0.0), 1e-9)
        assertEquals(-20.0, RumoCelular.diferenca(350.0, 10.0), 1e-9)
    }
}
