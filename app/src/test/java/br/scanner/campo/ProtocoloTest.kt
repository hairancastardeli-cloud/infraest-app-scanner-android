package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocoloTest {

    private fun quadroNuvem(n: Int, pose: FloatArray, estado: Int = 0, extra: Int = 0): ByteArray {
        val passo = 16
        val bb = ByteBuffer.allocate(48 + extra + n * passo)
        bb.order(ByteOrder.BIG_ENDIAN).putInt(0, n).putInt(4, passo).putLong(8, 123456789L).putInt(16, estado).putInt(44, extra)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 6) bb.putFloat(20 + 4 * i, pose[i])
        for (k in 0 until n) {
            val o = 48 + extra + k * passo
            bb.putFloat(o, k.toFloat()); bb.putFloat(o + 4, 2f * k); bb.putFloat(o + 8, -k.toFloat())
        }
        return bb.array()
    }

    @Test fun montaComandoIgualAoFireEye() {
        val q = Protocolo.montar(Protocolo.PARAR)
        assertEquals("HOLD", String(q, 0, 4))
        assertEquals(listOf(0x10, 0, 0, 2), q.copyOfRange(4, 8).map { it.toInt() and 0xff })
        assertEquals(0, ByteBuffer.wrap(q, 8, 4).int)
        val d = Protocolo.dadosAmostragem()
        assertEquals(29, d.size)                                     // float + int + double + double + byte + int
        val ini = Protocolo.dadosIniciar("Campo", "teste", 1000L)
        assertEquals(1, ini[0].toInt()); assertEquals("Campo_teste", String(ini, 1, 11))
        assertEquals(1000L, ByteBuffer.wrap(ini, 12, 8).long)
    }

    @Test fun remontaPacotesColadosEPartidos() {
        val a = Protocolo.montar(Protocolo.Tipo.GNSS.codigo, "\$GNGGA,teste".toByteArray())
        val b = Protocolo.montar(Protocolo.Tipo.NUVEM.codigo, quadroNuvem(10, floatArrayOf(1f, 2f, 3f, 0f, 0f, 90f)))
        val c = Protocolo.montar(Protocolo.Tipo.BATERIA.codigo, ByteArray(7))
        val fluxo = byteArrayOf(9, 9, 9) + a + b + c                // lixo antes + 3 mensagens
        val r = Protocolo.Remontador()
        val saida = ArrayList<Protocolo.Mensagem>()
        var i = 0
        val tamanhos = intArrayOf(1, 5, 13, 2, 50, 7, 1000)          // pedaços de tamanhos quaisquer
        var k = 0
        while (i < fluxo.size) {
            val n = minOf(tamanhos[k++ % tamanhos.size], fluxo.size - i)
            saida += r.alimentar(fluxo.copyOfRange(i, i + n))
            i += n
        }
        assertEquals(listOf(Protocolo.Tipo.GNSS, Protocolo.Tipo.NUVEM, Protocolo.Tipo.BATERIA), saida.map { it.tipo })
        assertEquals(3L, r.descartados)
        // tudo de uma vez também
        val r2 = Protocolo.Remontador()
        assertEquals(3, r2.alimentar(a + b + c).size)
    }

    @Test fun leQuadroDeNuvem() {
        val d = quadroNuvem(5, floatArrayOf(10f, 20f, 1.5f, 0.5f, -0.5f, 45f), estado = 3, extra = 20)
        val q = Protocolo.lerNuvem(d)
        assertNotNull(q)
        q!!
        assertEquals(5, q.nPontos); assertEquals(3, q.estado); assertEquals(123456789L, q.tempo)
        assertEquals(10f, q.pose[0]); assertEquals(45f, q.pose[5])
        assertEquals(2f, q.pontos[3 * 2], 0f); assertEquals(8f, q.pontos[3 * 4 + 1], 0f)   // ponto k = (k, 2k, -k)
    }

    @Test fun leGga() {
        val corpo = "GNGGA,123519,2330.000,S,04630.000,W,4,18,0.7,545.4,M,46.9,M,1.0,0001"
        var x = 0; for (ch in corpo) x = x xor ch.code
        val g = Gga.ler("\$$corpo*%02X".format(x))
        assertNotNull(g); g!!
        assertEquals(4, g.qualidade); assertEquals(18, g.satelites)
        assertTrue(g.resumo().contains("RTK FIXO"))
        assertEquals(null, Gga.ler("\$$corpo*00"))                // checksum errado
    }
}

class ProtocoloGnssTest {
    @Test fun gprmcComLixoAntesDoCifrao() {
        val corpo = "GNGGA,123519.00,2330.1234,S,04630.5678,W,5,14,0.9,545.4,M,-7.0,M,2.0,0001"
        var x = 0; for (ch in corpo) x = x xor ch.code
        val texto = "\u0001\u0002junk\$$corpo*%02X\r\n".format(x)
        val g = Gga.ler(texto)
        org.junit.Assert.assertNotNull(g); g!!
        org.junit.Assert.assertEquals(5, g.qualidade); org.junit.Assert.assertEquals(14, g.satelites)
        org.junit.Assert.assertEquals(2.0, g.idadeCorrecao!!, 0.0)
    }

    @Test fun estacaoBaseEhOGga() {
        org.junit.Assert.assertEquals(Protocolo.Tipo.ESTACAO_BASE, Protocolo.Tipo.de(byteArrayOf(0x11, 0, 0, 5)))
        org.junit.Assert.assertEquals(Protocolo.Tipo.GNSS, Protocolo.Tipo.de(byteArrayOf(0x11, 0, 0, 4)))
    }

    @Test fun bateriaEMemoria() {
        // 15600 mV = 3C F0 → 85%
        val d = byteArrayOf(0, 0, 0, 0, 0, 0x3C, 0xF0.toByte())
        org.junit.Assert.assertEquals(15600 to 85, Protocolo.lerBateria(d))
        org.junit.Assert.assertEquals(12 to 64, Protocolo.lerMemoria(byteArrayOf(12, 64)))
        org.junit.Assert.assertEquals(3, Protocolo.lerEstadoSolucao(byteArrayOf(0, 0, 0, 3)))
    }
}

class PrecisaoTest {
    private fun gga(q: Int) = Gga(q, 14, 0.8, 2.0, 545.0)

    @Test fun estimativaPeloTipoDeSolucao() {
        org.junit.Assert.assertEquals("≈ 2 cm", Precisao.de(gga(4), null).valor)
        org.junit.Assert.assertEquals(1, Precisao.de(gga(5), null).nivel)
        org.junit.Assert.assertEquals(-1, Precisao.de(gga(0), null).nivel)
        org.junit.Assert.assertEquals("RTK fixo", gga(4).nomeSolucao())
    }

    @Test fun precisaoMedidaPeloGst() {
        val gst = Gst.ler("\$GNGST,123519.00,0.8,0.02,0.01,45.0,0.012,0.016,0.03*5A")
        org.junit.Assert.assertNotNull(gst); gst!!
        org.junit.Assert.assertEquals(0.02, gst.horizontal, 1e-9)          // sqrt(0,012² + 0,016²)
        val p = Precisao.de(gga(4), gst)
        org.junit.Assert.assertEquals("±2.0 cm".replace('.', ','), p.valor.replace('.', ','))
        org.junit.Assert.assertEquals(2, p.nivel)
        org.junit.Assert.assertNull(Gst.ler("\$GNGGA,sem,gst"))
    }
}
