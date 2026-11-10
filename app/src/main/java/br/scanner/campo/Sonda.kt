package br.scanner.campo

import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/**
 * Sonda de portas TCP do scanner (só leitura: abre e fecha a conexão, não manda nada). Serve para saber se o scanner
 * tem algum acesso além do 8080 do DataManager (adb por Wi-Fi 5555, SSH 22, telnet 23…), o caminho para ligar o GNSS
 * sem iniciar o levantamento sem precisar do cabo USB no PC.
 */
object Sonda {
    val CONHECIDAS = mapOf(21 to "FTP", 22 to "SSH", 23 to "telnet", 80 to "HTTP", 1234 to "gdbserver", 5037 to "adb (servidor)",
        5555 to "adb por Wi-Fi", 8080 to "DataManager (app)", 8081 to "?", 5900 to "VNC")

    fun nome(p: Int) = CONHECIDAS[p] ?: "desconhecida"

    /**
     * Testa as portas [de]..[ate] em [paralelo] conexões simultâneas. [progresso] recebe (testadas, total).
     * Devolve as portas abertas em ordem.
     */
    fun varrer(fabrica: SocketFactory, ip: String, de: Int = 1, ate: Int = 65535, prazoMs: Int = 400, paralelo: Int = 64,
               pular: Set<Int> = emptySet(), progresso: (Int, Int) -> Unit = { _, _ -> }): List<Int> {
        val abertas = Collections.synchronizedList(ArrayList<Int>())
        val feitas = AtomicInteger()
        val portas = (de..ate).filter { it !in pular }
        val total = portas.size
        val ex = Executors.newFixedThreadPool(paralelo)
        for (p in portas) ex.execute {
            try {
                fabrica.createSocket().use { s -> s.connect(InetSocketAddress(ip, p), prazoMs); abertas.add(p) }
            } catch (_: Exception) { }
            val n = feitas.incrementAndGet()
            if (n % 500 == 0 || n == total) progresso(n, total)
        }
        ex.shutdown()
        ex.awaitTermination(30, TimeUnit.MINUTES)
        return abertas.sorted()
    }

    fun relatorio(abertas: List<Int>): String = if (abertas.isEmpty()) "nenhuma porta aberta além do 8080 do app"
        else abertas.joinToString("\n") { "porta $it aberta (${nome(it)})" }
}
