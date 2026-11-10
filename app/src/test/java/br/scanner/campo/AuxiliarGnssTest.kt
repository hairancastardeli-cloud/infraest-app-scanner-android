package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.concurrent.thread

class AuxiliarGnssTest {
    private val script = File("src/main/assets/scanner_campo_gnss.py").readText()

    @Test fun scriptVaiEmPedacosPequenosEVoltaIgual() {
        val cmds = AuxiliarGnss.comandosInstalar(script)
        assertTrue(cmds.all { it.length < 3500 })
        val b64 = cmds.filter { it.startsWith("printf %s '") }.joinToString("") { it.substringAfter("printf %s '").substringBefore("'") }
        assertEquals(script, String(Base64.getDecoder().decode(b64)))
        assertTrue(cmds.first().contains("scanner_campo_gnss[.]py"))
        // o pkill/pgrep mataria/acharia o próprio shell se o comando tivesse o nome do script por extenso
        assertTrue(!cmds.first().contains("scanner_campo_gnss.py") && !cmds.last().contains("scanner_campo_gnss.py"))
        assertTrue(cmds.last().contains("AUX_OK"))
    }

    private fun lerAdb(ent: DataInputStream): Adb.Msg {
        val cab = ByteArray(24); ent.readFully(cab)
        val b = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val n = b.int
        val d = ByteArray(n); ent.readFully(d)
        return Adb.Msg(cmd, a0, a1, d)
    }

    /** adbd que ecoa o comando (o último contém "echo AUX_OK", então a instalação "dá certo"). */
    private fun adbdEco(srv: ServerSocket) = thread(isDaemon = true) {
        srv.accept().use { s ->
            val ent = DataInputStream(s.getInputStream()); val sai = s.getOutputStream()
            lerAdb(ent)
            sai.write(Adb.montar(Adb.CNXN, Adb.VERSAO, 4096, "device::\u0000".toByteArray()))
            var id = 50
            while (true) {
                val o = try { lerAdb(ent) } catch (_: Exception) { return@thread }
                if (o.cmd != Adb.OPEN) continue
                val meu = id++
                sai.write(Adb.montar(Adb.OKAY, meu, o.a0))
                sai.write(Adb.montar(Adb.WRTE, meu, o.a0, o.dados.copyOf(o.dados.size - 1)))
                lerAdb(ent)
                sai.write(Adb.montar(Adb.CLSE, meu, o.a0))
                lerAdb(ent)
            }
        }
    }

    @Test fun recebeOGgaEMandaORtcm() {
        val lo = InetAddress.getLoopbackAddress()
        ServerSocket(0, 5, lo).use { adbSrv -> ServerSocket(0, 5, lo).use { auxSrv ->
            adbdEco(adbSrv)
            val rtcm = CountDownLatch(1)
            var recebido = ByteArray(0)
            thread(isDaemon = true) {
                auxSrv.accept().use { s ->
                    s.getOutputStream().write("#OK\n\$GPGGA,1,2*00\nlixo\n#POS\n".toByteArray())
                    val b = ByteArray(5); DataInputStream(s.getInputStream()).readFully(b); recebido = b; rtcm.countDown()
                    Thread.sleep(500)
                }
            }
            val linhas = CopyOnWriteArrayList<String>()
            val estados = CopyOnWriteArrayList<AuxiliarGnss.Estado>()
            val pos = CountDownLatch(1)
            val aux = AuxiliarGnss(SocketFactory.getDefault(), script, { linhas.add(it) },
                { e, _ -> estados.add(e); if (e == AuxiliarGnss.Estado.LEVANTAMENTO) pos.countDown() },
                "127.0.0.1", adbSrv.localPort, auxSrv.localPort)
            aux.iniciar()
            assertTrue(pos.await(10, TimeUnit.SECONDS))
            aux.enviarRtcm(byteArrayOf(0xD3.toByte(), 0, 1, 2, 3))
            assertTrue(rtcm.await(5, TimeUnit.SECONDS))
            assertEquals(0xD3.toByte(), recebido[0])
            assertEquals(listOf("\$GPGGA,1,2*00"), linhas.toList())
            assertTrue(estados.containsAll(listOf(AuxiliarGnss.Estado.INSTALANDO, AuxiliarGnss.Estado.LIGADO)))
            aux.parar()
        } }
    }
}
