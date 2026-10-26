package br.scanner.campo

import android.net.Network
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Conexão TCP com o scanner (192.168.2.11:8080). O socket é criado PRESO à rede Wi-Fi do scanner (a `rede` que o
 * ConectorScanner obteve do Android), enquanto o resto do app (NTRIP) usa os dados móveis. Se o scanner fechar a
 * conexão, tenta de novo (espera crescente) enquanto a rede existir.
 */
class Equipamento(
    private val aoReceber: (Protocolo.Mensagem) -> Unit,
    private val aoMudarEstado: (conectado: Boolean, texto: String) -> Unit,
) {
    var ip = ConectorScanner.IP
    var porta = ConectorScanner.PORTA

    private val ligado = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var saida: OutputStream? = null
    private val envio = Executors.newSingleThreadExecutor()
    private var thread: Thread? = null
    @Volatile var conectado = false
        private set
    @Volatile var bytesRecebidos = 0L
        private set

    /** Começa a conectar pela rede dada (null = rede padrão, usado só nos testes). */
    fun iniciar(rede: Network?) {
        parar()
        ligado.set(true)
        thread = Thread({ laco(rede) }, "scanner-tcp").apply { isDaemon = true; start() }
    }

    fun parar() {
        ligado.set(false)
        fechar()
        thread?.interrupt(); thread = null
    }

    val ativo: Boolean get() = ligado.get()

    fun enviar(comando: ByteArray, dados: ByteArray = ByteArray(0)): Boolean {
        val out = saida ?: return false
        val quadro = Protocolo.montar(comando, dados)
        envio.execute {
            try {
                synchronized(this) { out.write(quadro); out.flush() }
            } catch (e: Exception) {
                fechar()
            }
        }
        return true
    }

    private fun laco(rede: Network?) {
        var espera = 1000L
        var tentativas = 0
        while (ligado.get()) {
            try {
                aoMudarEstado(false, if (tentativas == 0) "abrindo a conexão com o scanner…" else "reconectando ($tentativas)…")
                val s = rede?.socketFactory?.createSocket() ?: Socket()
                s.tcpNoDelay = true; s.keepAlive = true
                s.receiveBufferSize = 4 * 1024 * 1024
                s.connect(InetSocketAddress(ip, porta), 5000)
                s.soTimeout = 2000                           // acorda a cada 2 s para ver se ainda deve ler
                socket = s; saida = s.getOutputStream()
                conectado = true; espera = 1000L; tentativas = 0
                aoMudarEstado(true, "conectado ao scanner")
                ler(s.getInputStream())
            } catch (e: Exception) {
                if (ligado.get()) aoMudarEstado(false, "sem resposta do scanner (${e.message ?: e.javaClass.simpleName})")
            } finally {
                fechar()
            }
            if (ligado.get()) {
                tentativas++
                try { Thread.sleep(espera) } catch (_: InterruptedException) { return }
                espera = minOf(espera * 2, 8000L)
            }
        }
        aoMudarEstado(false, "desconectado")
    }

    private fun ler(ent: InputStream) {
        val rem = Protocolo.Remontador()
        val b = ByteArray(256 * 1024)
        while (ligado.get()) {
            val n = try {
                ent.read(b)
            } catch (_: java.net.SocketTimeoutException) {
                continue                                     // scanner parado sem mandar nada: segue esperando
            }
            if (n < 0) throw java.io.EOFException("o scanner fechou a conexão")
            bytesRecebidos += n
            for (m in rem.alimentar(b, n)) {
                try { aoReceber(m) } catch (_: Exception) { }
            }
        }
    }

    private fun fechar() {
        conectado = false
        saida = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }
}
