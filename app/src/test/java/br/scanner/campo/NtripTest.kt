package br.scanner.campo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Caster NTRIP simulado num socket local: confere o pedido e responde como os casters reais. */
class NtripTest {
    private val pedidos = Collections.synchronizedList(ArrayList<String>())
    private val ggaRecebidos = Collections.synchronizedList(ArrayList<String>())
    private var servidor: ServerSocket? = null

    private val tabela = "STR;BASE1;Sao Paulo;RTCM 3.2;1004(1),1012(1);2;GPS+GLO;RBMC;BRA;-23.55;-46.63;1;0;sNTRIP;none;B;N;9600;\r\n" +
        "STR;AUTO;Rede;RTCM 3.3;;2;GPS+GLO+GAL+BDS;REDE;BRA;-23.0;-46.0;1;1;sNTRIP;none;B;N;9600;\r\n" +
        "ENDSOURCETABLE\r\n"

    private fun caster(resposta: (pedido: String) -> (java.io.OutputStream) -> Unit): Int {
        val s = ServerSocket(0); servidor = s
        Thread {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                Thread {
                    c.use {
                        val ent = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
                        val sb = StringBuilder()
                        while (true) { val l = ent.readLine() ?: break; if (l.isEmpty()) break; sb.append(l).append('\n') }
                        pedidos.add(sb.toString())
                        val out = c.getOutputStream()
                        resposta(sb.toString())(out)
                        try {                                         // o que vier depois é GGA
                            while (true) { val l = ent.readLine() ?: break; if (l.isNotBlank()) ggaRecebidos.add(l) }
                        } catch (_: Exception) { }
                    }
                }.start()
            }
        }.start()
        return s.localPort
    }

    @After fun fim() { servidor?.close() }

    @Test fun obtemMountpointsNtrip1SemSenha() {
        val porta = caster { _ -> { o -> o.write(("SOURCETABLE 200 OK\r\nContent-Type: text/plain\r\n\r\n$tabela").toByteArray()); o.flush(); Thread.sleep(200); o.close() } }
        val n = Ntrip(null, {}, { _, _ -> })
        val lista = n.obterMountpoints(Ntrip.Config("127.0.0.1", porta, "", "", ""))
        assertEquals(listOf("AUTO", "BASE1"), lista.map { it.nome })
        assertTrue(lista.first { it.nome == "AUTO" }.pedeGga)
        assertTrue(pedidos[0].startsWith("GET / HTTP/1.0"))
        assertTrue(!pedidos[0].contains("Authorization"))         // sem usuário: não manda senha
    }

    @Test fun obtemMountpointsNtrip2Chunked() {
        val porta = caster { _ -> { o ->
            val corpo = tabela.toByteArray()
            o.write("HTTP/1.1 200 OK\r\nContent-Type: gnss/sourcetable\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            o.write("%x\r\n".format(50).toByteArray()); o.write(corpo, 0, 50); o.write("\r\n".toByteArray())
            o.write("%x\r\n".format(corpo.size - 50).toByteArray()); o.write(corpo, 50, corpo.size - 50); o.write("\r\n0\r\n\r\n".toByteArray())
            o.flush(); Thread.sleep(200); o.close()
        } }
        val lista = Ntrip(null, {}, { _, _ -> }).obterMountpoints(Ntrip.Config("127.0.0.1", porta, "u", "s", ""))
        assertEquals(2, lista.size)
        assertTrue(pedidos[0].contains("Authorization: Basic dTpz"))   // "u:s"
    }

    @Test fun senhaRecusadaDaMensagemClara() {
        val porta = caster { _ -> { o -> o.write("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray()); o.flush(); o.close() } }
        try {
            Ntrip(null, {}, { _, _ -> }).obterMountpoints(Ntrip.Config("127.0.0.1", porta, "x", "y", ""))
            fail("devia dar erro")
        } catch (e: Ntrip.ErroNtrip) {
            assertTrue(e.message!!.contains("senha"))
        }
    }

    @Test fun conectaRecebeRtcmEMandaGga() {
        val rtcm = ByteArray(300) { (it % 251).toByte() }
        val porta = caster { _ -> { o ->
            o.write("ICY 200 OK\r\n\r\n".toByteArray()); o.flush()
            repeat(5) { o.write(rtcm); o.flush(); Thread.sleep(300) }
        } }
        val recebido = java.io.ByteArrayOutputStream()
        val chegou = CountDownLatch(1)
        val n = Ntrip(null, { b -> synchronized(recebido) { recebido.write(b); if (recebido.size() >= 1500) chegou.countDown() } }, { _, _ -> })
        n.intervaloGgaS = 1
        n.atualizarGga("\$GNGGA,123519,2330.000,S,04630.000,W,1,18,0.7,545.4,M,46.9,M,,*47")
        n.conectar(Ntrip.Config("127.0.0.1", porta, "u", "s", "BASE1"))
        assertTrue("RTCM não chegou", chegou.await(10, TimeUnit.SECONDS))
        Thread.sleep(1500)
        n.desconectar()
        assertTrue(pedidos[0].startsWith("GET /BASE1 HTTP/1.0"))
        assertEquals(1500, recebido.size()); assertEquals(1500L, n.bytesRtcm)
        assertTrue("GGA não foi ao caster", ggaRecebidos.any { it.startsWith("\$GNGGA") })
        assertTrue("o pedido HTTP não pode ser reenviado", pedidos.size == 1 && ggaRecebidos.none { it.startsWith("GET") })
    }

    @Test fun mountpointInexistenteParaDeTentar() {
        val porta = caster { _ -> { o -> o.write(("SOURCETABLE 200 OK\r\n\r\n$tabela").toByteArray()); o.flush(); o.close() } }
        val estados = Collections.synchronizedList(ArrayList<String>())
        val n = Ntrip(null, {}, { e, t -> estados.add("$e:$t") })
        n.conectar(Ntrip.Config("127.0.0.1", porta, "", "", "NAOEXISTE"))
        Thread.sleep(2500)
        assertTrue(estados.toString(), estados.any { it.contains("não existe") })
        assertTrue("não devia ficar tentando", !n.conectado)
        assertEquals(1, pedidos.size)
    }
}
