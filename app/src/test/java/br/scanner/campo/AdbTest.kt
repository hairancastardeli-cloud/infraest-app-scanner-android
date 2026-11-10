package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.SocketFactory
import kotlin.concurrent.thread

class AdbTest {
    private fun ler(ent: DataInputStream): Adb.Msg {
        val cab = ByteArray(24); ent.readFully(cab)
        val b = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val n = b.int; val soma = b.int; val magic = b.int
        assertEquals(cmd xor -1, magic)
        val d = ByteArray(n); ent.readFully(d)
        assertEquals(d.sumOf { it.toInt() and 0xff }, soma)
        return Adb.Msg(cmd, a0, a1, d)
    }

    /** adbd simulado: aceita a conexão e responde cada "shell:X" com "saida de X" em dois pedaços. */
    private fun adbdFalso(srv: ServerSocket, pedirAuth: Boolean = false) = thread(isDaemon = true) {
        srv.accept().use { s ->
            val ent = DataInputStream(s.getInputStream()); val sai = s.getOutputStream()
            val c = ler(ent)
            assertEquals(Adb.CNXN, c.cmd)
            if (pedirAuth) { sai.write(Adb.montar(Adb.AUTH, 1, 0, ByteArray(20))); return@thread }
            sai.write(Adb.montar(Adb.CNXN, Adb.VERSAO, 4096, "device::ro.product.name=firefly;\u0000".toByteArray()))
            var remoto = 100
            while (true) {
                val o = try { ler(ent) } catch (_: Exception) { return@thread }
                if (o.cmd != Adb.OPEN) continue
                val cmd = String(o.dados).removePrefix("shell:").trimEnd('\u0000')
                val meu = remoto++
                sai.write(Adb.montar(Adb.OKAY, meu, o.a0))
                for (parte in listOf("saida ", "de $cmd")) {
                    sai.write(Adb.montar(Adb.WRTE, meu, o.a0, parte.toByteArray()))
                    assertEquals(Adb.OKAY, ler(ent).cmd)
                }
                sai.write(Adb.montar(Adb.CLSE, meu, o.a0))
                assertEquals(Adb.CLSE, ler(ent).cmd)
            }
        }
    }

    @Test fun rodaComandosNoShell() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { srv ->
            adbdFalso(srv)
            Adb(SocketFactory.getDefault(), "127.0.0.1", srv.localPort).use { adb ->
                adb.conectar()
                assertTrue(adb.banner.startsWith("device::"))
                assertEquals("saida de uname -a", adb.shell("uname -a"))
                assertEquals("saida de ls", adb.shell("ls"))
            }
        }
    }

    @Test fun avisaQuandoPedeChave() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { srv ->
            adbdFalso(srv, pedirAuth = true)
            Adb(SocketFactory.getDefault(), "127.0.0.1", srv.localPort).use { adb ->
                try { adb.conectar(); fail("devia pedir AUTH") } catch (e: Adb.ErroAdb) { assertTrue(e.message!!.contains("AUTH")) }
            }
        }
    }

    @Test fun investigacaoRodaTodosOsItens() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { srv ->
            adbdFalso(srv)
            val r = InvestigacaoGnss.rodar(SocketFactory.getDefault(), "127.0.0.1", srv.localPort)
            for ((titulo, cmd) in InvestigacaoGnss.COMANDOS) {
                assertTrue(titulo, r.contains("### $titulo"))
                assertTrue(titulo, r.contains("saida de $cmd"))
            }
        }
    }
}
