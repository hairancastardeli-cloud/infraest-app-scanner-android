package br.scanner.campo

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.SocketFactory

/**
 * GNSS do scanner SEM iniciar o levantamento. O receptor (Unicore, /dev/ttyS1 a 460800) só fala depois de configurado, e
 * no software de fábrica só o programa "pos" (aberto pelo DataManager ao iniciar) faz isso. Aqui o app instala pelo adb
 * (Wi-Fi, root) o script assets/scanner_campo_gnss.py em /tmp do scanner (some ao reiniciar; nada é gravado no aparelho),
 * que pede o GGA/GST ao receptor e o serve na porta 8081; a correção RTCM do caster vai pelo mesmo socket ao receptor.
 * Quando um levantamento começa o script solta a serial ("#POS") e o GNSS volta a chegar pelo DataManager.
 */
class AuxiliarGnss(
    private val fabrica: SocketFactory,
    private val script: String,
    private val aoLinha: (String) -> Unit,
    private val aoEstado: (Estado, String) -> Unit,
    private val ip: String = ConectorScanner.IP,
    private val portaAdb: Int = 5555,
    private val portaAux: Int = 8081,
) {
    enum class Estado { INSTALANDO, LIGADO, LEVANTAMENTO, ERRO, DESLIGADO }

    companion object {
        const val ARQ = "/tmp/scanner_campo_gnss.py"

        /**
         * Comandos de shell, em ordem. O script vai em pedaços de base64 (o adbd antigo limita o comando a 4 KB). O pkill usa
         * "[.]" para não achar o próprio shell que o roda.
         */
        fun comandosInstalar(script: String): List<String> {
            val b64 = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
            // nada neste comando pode ter o nome do script por extenso (o pkill mataria o próprio shell): o rm usa "[.]" também
            val l = arrayListOf("pkill -f 'scanner_campo_gnss[.]py'; sleep 0.5; rm -f /tmp/scanner_campo_gnss[.]py.b64; echo parado")
            for (pedaco in b64.chunked(2000)) l.add("printf %s '$pedaco' >> $ARQ.b64; echo ok")
            l.add("base64 -d $ARQ.b64 > $ARQ && rm -f $ARQ.b64 && cd /tmp && " +
                "setsid nohup python3 $ARQ > /tmp/scanner_campo_gnss.log 2>&1 < /dev/null & sleep 1.5; echo lancado")
            l.add("pgrep -f 'scanner_campo_gnss[.]py' > /dev/null && echo AUX_OK || { echo AUX_FALHOU; tail -5 /tmp/scanner_campo_gnss.log; }")
            return l
        }
    }

    @Volatile var ativo = false
        private set
    @Volatile var estado = Estado.DESLIGADO
        private set
    @Volatile private var socket: Socket? = null
    @Volatile private var saida: OutputStream? = null
    private var thread: Thread? = null

    fun iniciar() {
        if (ativo) return
        ativo = true
        thread = Thread({ laco() }, "gnss-aux").apply { isDaemon = true; start() }
    }

    /** Pede ao script para sair ("#FIM": ele deixa o receptor mudo de novo). Pode ser chamado da tela: a rede vai noutra thread. */
    fun parar() {
        ativo = false
        val o = saida; val s = socket
        socket = null; saida = null
        Thread({
            try { o?.write("#FIM\n".toByteArray()); o?.flush() } catch (_: Exception) { }
            try { s?.close() } catch (_: Exception) { }
        }, "gnss-aux-fim").apply { isDaemon = true; start() }
        thread?.interrupt(); thread = null
        mudar(Estado.DESLIGADO, "GNSS sem levantamento desligado")
    }

    /** Correção do caster para o receptor (ignorada se o auxiliar não estiver conectado). */
    fun enviarRtcm(b: ByteArray) {
        val o = saida ?: return
        try { synchronized(this) { o.write(b); o.flush() } } catch (_: Exception) { fechar() }
    }

    private fun mudar(e: Estado, t: String) { estado = e; aoEstado(e, t) }

    private fun instalar(): String? {
        mudar(Estado.INSTALANDO, "instalando o auxiliar no scanner (adb)…")
        return try {
            Adb(fabrica, ip, portaAdb).use { adb ->
                adb.conectar()
                var ultima = ""
                for (c in comandosInstalar(script)) ultima = adb.shell(c, 20_000)
                if (ultima.contains("AUX_OK")) null else "o auxiliar não ficou rodando: " + ultima.replace("AUX_FALHOU", "").trim().take(200)
            }
        } catch (e: Exception) { "adb: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun conectarAux(): Boolean {
        for (k in 0 until 8) {
            if (!ativo) return false
            try {
                val s = fabrica.createSocket()
                s.connect(InetSocketAddress(ip, portaAux), 2000)
                s.soTimeout = 5000; s.tcpNoDelay = true
                socket = s; saida = s.getOutputStream()
                return true
            } catch (_: Exception) { try { Thread.sleep(700) } catch (_: InterruptedException) { return false } }
        }
        return false
    }

    private fun laco() {
        var instalado = false
        var falhas = 0
        while (ativo) {
            if (!instalado) {
                val erro = instalar()
                if (erro != null) { ativo = false; mudar(Estado.ERRO, erro); return }
                instalado = true
            }
            if (!conectarAux()) {
                falhas++
                if (falhas >= 2) { ativo = false; mudar(Estado.ERRO, "o auxiliar não respondeu na porta $portaAux"); return }
                instalado = false; continue                               // reinstala uma vez
            }
            falhas = 0
            mudar(Estado.LIGADO, "auxiliar ligado; aguardando o receptor…")
            try {
                val r = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.ISO_8859_1))
                while (ativo) {
                    val l = try { r.readLine() } catch (_: java.net.SocketTimeoutException) { throw java.io.IOException("auxiliar calado há 5 s") } ?: break
                    when {
                        l == "#POS" -> if (estado != Estado.LEVANTAMENTO) mudar(Estado.LEVANTAMENTO, "levantamento rodando: o GNSS vem pelo levantamento")
                        l == "#OK" -> if (estado != Estado.LIGADO) mudar(Estado.LIGADO, "auxiliar ligado")
                        l.startsWith("#ERRO") -> mudar(Estado.INSTALANDO, "auxiliar: " + l.removePrefix("#ERRO").trim().take(120))
                        l.startsWith("$") -> aoLinha(l)
                    }
                }
            } catch (_: Exception) { }
            fechar()
            if (ativo) { mudar(Estado.INSTALANDO, "conexão com o auxiliar caiu; reconectando…"); try { Thread.sleep(1000) } catch (_: InterruptedException) { return } }
        }
    }

    private fun fechar() {
        saida = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }
}
