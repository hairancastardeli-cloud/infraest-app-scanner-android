package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import javax.net.SocketFactory

class SondaTest {
    @Test fun achaAPortaAbertaEOuveOProgresso() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { srv ->
            val p = srv.localPort
            var fim = 0
            val r = Sonda.varrer(SocketFactory.getDefault(), "127.0.0.1", p - 3, p + 3, 300, 4, setOf(p + 1)) { n, _ -> fim = n }
            assertTrue(r.contains(p))
            assertEquals(6, fim)
        }
    }

    @Test fun relatorioNomeiaAsPortas() {
        assertTrue(Sonda.relatorio(listOf(5555)).contains("adb por Wi-Fi"))
        assertEquals("nenhuma porta aberta além do 8080 do app", Sonda.relatorio(emptyList()))
    }
}
