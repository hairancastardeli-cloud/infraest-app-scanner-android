package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos

class PontoGnssTest {
    // valores de referência do pyproj (EPSG 4674 → 31982/31983/31984)
    @Test fun utmIgualAoPyproj() {
        val sp = Geodesia.utm(-23.550520, -46.633308)
        assertEquals(23, sp.fuso); assertTrue(sp.sul)
        assertEquals(333287.1236, sp.e, 0.001); assertEquals(7394586.0946, sp.n, 0.001)
        val ssa = Geodesia.utm(-12.97, -38.501)
        assertEquals(24, ssa.fuso)
        assertEquals(554119.3513, ssa.e, 0.001); assertEquals(8566128.9666, ssa.n, 0.001)
        val poa = Geodesia.utm(-30.03, -51.2)
        assertEquals(22, poa.fuso)
        assertEquals(480716.2477, poa.e, 0.001); assertEquals(6677873.5533, poa.n, 0.001)
    }

    /** GGA com checksum certo para lat/lon em graus decimais. */
    private fun gga(hora: String, lat: Double, lon: Double, alt: Double, q: Int = 4, sep: Double = -5.0): Gga {
        fun dm(v: Double, g: Int): String {
            val a = Math.abs(v); val d = a.toInt()
            return "%0${g}d%010.7f".format(java.util.Locale.US, d, (a - d) * 60)
        }
        val corpo = "GPGGA,$hora,${dm(lat, 2)},${if (lat < 0) "S" else "N"},${dm(lon, 3)},${if (lon < 0) "W" else "E"}," +
            "$q,18,0.7,${"%.3f".format(java.util.Locale.US, alt)},M,${"%.3f".format(java.util.Locale.US, sep)},M,1.0,0001"
        var x = 0; for (ch in corpo) x = x xor ch.code
        return Gga.ler("$" + corpo + "*%02X".format(x))!!
    }

    @Test fun ggaTrazSeparacaoEHora() {
        val g = gga("123519.00", -23.5, -46.6, 760.0, sep = -5.2)
        assertEquals("123519.00", g.hora)
        assertEquals(754.8, g.alturaElipsoidal!!, 1e-6)
    }

    @Test fun duasLeiturasGiradasCancelamOAfastamento() {
        val lat0 = -23.55; val lon0 = -46.63
        val mLat = 1 / 111_000.0; val mLon = 1 / (111_000.0 * cos(Math.toRadians(lat0)))
        // antena 0,30 m a leste da ponta na 1ª leitura, 0,30 m a oeste na 2ª (girou 180°); 0,253 m acima da ponta
        val c1 = PontoGnss.Coletor(5, false)
        for (s in 0 until 6) c1.adicionar(gga("1200%02d.00".format(s), lat0, lon0 + 0.30 * mLon, 700.253), 1000L + s * 1000L)
        c1.adicionar(gga("120005.00", lat0, lon0, 999.0), 5000L)          // época repetida: não entra
        c1.adicionar(gga("120010.00", lat0, lon0, 999.0, q = 5), 6000L)  // flutuante: não entra
        assertEquals(6, c1.aceitas); assertEquals(1, c1.rejeitadas)
        assertTrue(c1.completo(6000L))
        val c2 = PontoGnss.Coletor(5, false, c1.fusoAtual())
        for (s in 0 until 6) c2.adicionar(gga("1201%02d.00".format(s), lat0 + 0.0 * mLat, lon0 - 0.30 * mLon, 700.253), 1000L + s * 1000L)
        val r = PontoGnss.Resultado("PC1", "marco", PontoGnss.Suporte.BRACO, PontoGnss.ALTURA_BRACO, c1.leitura()!!, c2.leitura()!!)
        val ref = Geodesia.utm(lat0, lon0)
        assertEquals(ref.e, r.e, 0.005); assertEquals(ref.n, r.n, 0.005)
        assertEquals(0.30, r.afastamento, 0.01)
        assertEquals(695.0, r.hElip, 1e-3)            // 700,253 − 5 (geoide) − 0,253
        assertEquals(700.0, r.altitude, 1e-3)
        assertTrue(r.avisos().isEmpty())
    }

    @Test fun avisaQuandoNaoGirouOuDesnivelou() {
        val c1 = PontoGnss.Coletor(1, false)
        for (s in 0 until 5) c1.adicionar(gga("1300%02d.00".format(s), -20.0, -44.0, 800.0), 1000L + s * 1000L)
        val c2 = PontoGnss.Coletor(1, false, c1.fusoAtual())
        for (s in 0 until 5) c2.adicionar(gga("1301%02d.00".format(s), -20.0, -44.0, 800.10), 1000L + s * 1000L)
        val r = PontoGnss.Resultado("1", "", PontoGnss.Suporte.BASTAO, 2.0, c1.leitura()!!, c2.leitura()!!)
        val av = r.avisos().joinToString(" | ")
        assertTrue(av, av.contains("girado 180°"))
        assertTrue(av, av.contains("nivelado"))
    }

    @Test fun csvGravaLeEApaga() {
        val c1 = PontoGnss.Coletor(1, false)
        for (s in 0 until 5) c1.adicionar(gga("1400%02d.00".format(s), -20.0, -44.0, 800.0), 1000L + s * 1000L)
        val l = c1.leitura()!!
        val f = PontoGnss.arquivo(Files.createTempDirectory("pts").toFile(), "obra teste")
        assertEquals("pontos_rtk_obra_teste.csv", f.name)
        PontoGnss.salvar(f, PontoGnss.Resultado("PC1", "marco, muro", PontoGnss.Suporte.BRACO, 0.253, l, l))
        PontoGnss.salvar(f, PontoGnss.Resultado("PC2", "", PontoGnss.Suporte.BRACO, 0.253, l, l))
        val pts = PontoGnss.ler(f)
        assertEquals(2, pts.size)
        assertEquals("marco  muro", pts[0].descricao)                     // vírgula não quebra o CSV
        assertEquals("23S", pts[0].zona)
        assertTrue(File(f.path).readLines()[0].startsWith("Name,Easting,Northing,Elevation,Feature code"))
        PontoGnss.remover(f, 0)
        assertEquals(listOf("PC2"), PontoGnss.ler(f).map { it.nome })
    }

    @Test fun proximoNome() {
        assertEquals("1", PontoGnss.proximoNome(emptyList()))
        assertEquals("PC4", PontoGnss.proximoNome(listOf("PC3")))
        assertEquals("P010", PontoGnss.proximoNome(listOf("P009")))
        assertEquals("marco2", PontoGnss.proximoNome(listOf("marco")))
    }
}
