package br.scanner.campo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente NTRIP (caster RTK) com as duas operações separadas:
 *  - obterMountpoints(): pede a tabela de fontes ao caster e devolve a lista;
 *  - conectar(): abre o fluxo de correção do mountpoint escolhido e repassa o RTCM ao scanner.
 * Usuário/senha são opcionais (há caster aberto). A posição (GGA) vai ao caster só como a linha NMEA, a cada
 * [intervaloGgaS] s, no mesmo socket — o FireEye reenviava o pedido HTTP inteiro a cada segundo.
 */
class Ntrip(
    private val ctx: Context?,                // null nos testes (socket comum)
    private val aoRtcm: (ByteArray) -> Unit,
    private val aoEstado: (Estado, String) -> Unit,
) {
    enum class Estado { DESLIGADO, CONECTANDO, RECEBENDO, ERRO }

    data class Config(val host: String, val porta: Int, val usuario: String, val senha: String, val mountpoint: String)

    data class Mountpoint(
        val nome: String, val identificador: String, val formato: String, val sistemas: String,
        val pais: String, val lat: Double?, val lon: Double?, val pedeGga: Boolean, val autenticacao: String,
    ) {
        fun descricao(): String = buildString {
            append(nome)
            val extra = listOf(identificador, formato, sistemas, pais).filter { it.isNotBlank() }
            if (extra.isNotEmpty()) append("  —  ").append(extra.joinToString(" · "))
            if (pedeGga) append("  [precisa de posição]")
        }
    }

    class ErroNtrip(msg: String) : Exception(msg)

    var intervaloGgaS = 10
    @Volatile var bytesRtcm = 0L
        private set
    @Volatile var ultimoDadoMs = 0L
        private set
    @Volatile private var gga: String? = null
    @Volatile private var socket: Socket? = null
    private val ativo = AtomicBoolean(false)
    private var thread: Thread? = null

    fun atualizarGga(linha: String) {
        val l = linha.trim()
        if (l.startsWith("\$") && l.contains("GGA")) gga = l
    }

    // ------------------------------------------------------------------ tabela de fontes
    fun obterMountpoints(cfg: Config): List<Mountpoint> {
        validarEndereco(cfg)
        val s = abrir(cfg)
        try {
            enviarPedido(s.getOutputStream(), cfg, "", versao2 = false)
            val ent = BufferedInputStream(s.getInputStream())
            val status = lerLinha(ent) ?: throw ErroNtrip("o caster fechou a conexão sem responder")
            val cab = lerCabecalhos(ent)
            val corpo = when {
                status.startsWith("SOURCETABLE 200") -> lerAteFim(ent)
                status.contains(" 200") -> if (cab["transfer-encoding"]?.contains("chunked") == true) lerChunked(ent) else lerAteFim(ent)
                else -> throw ErroNtrip(traduzirStatus(status))
            }
            val lista = String(corpo, Charsets.ISO_8859_1).lineSequence()
                .filter { it.startsWith("STR;") }.mapNotNull { lerStr(it) }.toList()
            if (lista.isEmpty()) throw ErroNtrip("o caster respondeu, mas a tabela não tem nenhum mountpoint (STR)")
            return lista.sortedBy { it.nome.lowercase() }
        } finally {
            try { s.close() } catch (_: Exception) { }
        }
    }

    private fun lerStr(l: String): Mountpoint? {
        val c = l.split(";")
        if (c.size < 2 || c[1].isBlank()) return null
        fun g(i: Int) = c.getOrNull(i)?.trim() ?: ""
        return Mountpoint(
            nome = g(1), identificador = g(2), formato = g(3), sistemas = g(6), pais = g(8),
            lat = g(9).toDoubleOrNull(), lon = g(10).toDoubleOrNull(), pedeGga = g(11) == "1", autenticacao = g(15),
        )
    }

    // ------------------------------------------------------------------ fluxo de correção
    fun conectar(cfg: Config) {
        validarEndereco(cfg)
        if (cfg.mountpoint.isBlank()) throw ErroNtrip("escolha ou digite o mountpoint")
        desconectar()
        ativo.set(true)
        thread = Thread({ laco(cfg) }, "ntrip").apply { isDaemon = true; start() }
    }

    fun desconectar() {
        ativo.set(false)
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        thread?.interrupt()
        thread = null
    }

    val conectado: Boolean get() = ativo.get()

    private fun laco(cfg: Config) {
        var espera = 2000L
        var primeira = true
        while (ativo.get()) {
            try {
                aoEstado(Estado.CONECTANDO, if (primeira) "conectando em ${cfg.host}:${cfg.porta}/${cfg.mountpoint}…" else "reconectando…")
                val s = abrir(cfg); socket = s
                s.soTimeout = 1000
                val out = s.getOutputStream()
                enviarPedido(out, cfg, cfg.mountpoint, versao2 = false)
                val ent = BufferedInputStream(s.getInputStream())
                val status = lerLinhaComPrazo(ent, 15000) ?: throw ErroNtrip("o caster não respondeu em 15 s")
                var chunked = false
                when {
                    status.startsWith("ICY 200") -> pularLinhaEmBranco(ent)   // alguns casters mandam "\r\n" depois
                    status.startsWith("SOURCETABLE 200") ->
                        throw ErroNtrip("o mountpoint \"${cfg.mountpoint}\" não existe neste caster (ele devolveu a tabela)")
                    status.contains(" 200") -> chunked = lerCabecalhos(ent)["transfer-encoding"]?.contains("chunked") == true
                    else -> throw ErroNtrip(traduzirStatus(status))
                }
                aoEstado(Estado.RECEBENDO, "conectado; aguardando correção…")
                primeira = false; espera = 2000L
                gga?.let { enviarGga(out, it) }
                receber(ent, out, chunked)
            } catch (e: ErroNtrip) {
                if (ativo.get()) aoEstado(Estado.ERRO, e.message ?: "erro")
                if (e.message?.contains("senha") == true || e.message?.contains("não existe") == true) {
                    ativo.set(false); break                 // não adianta insistir
                }
            } catch (e: Exception) {
                if (ativo.get()) aoEstado(Estado.ERRO, "conexão com o caster caiu: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                try { socket?.close() } catch (_: Exception) { }
                socket = null
            }
            if (ativo.get()) {
                try { Thread.sleep(espera) } catch (_: InterruptedException) { }
                espera = minOf(espera * 2, 30000L)
            }
        }
        aoEstado(Estado.DESLIGADO, "RTK desligado")
    }

    private fun receber(ent: InputStream, out: OutputStream, chunked: Boolean) {
        val b = ByteArray(4096)
        var proxGga = System.currentTimeMillis() + intervaloGgaS * 1000L
        var ultimo = System.currentTimeMillis()
        var restoChunk = 0
        while (ativo.get()) {
            val agora = System.currentTimeMillis()
            if (agora >= proxGga) {
                gga?.let { enviarGga(out, it) }
                proxGga = agora + intervaloGgaS * 1000L
            }
            if (agora - ultimo > 30000) throw ErroNtrip("30 s sem correção do caster")
            val n = try {
                if (chunked) {
                    if (restoChunk == 0) {
                        val linha = lerLinha(ent) ?: throw java.io.EOFException()
                        if (linha.isBlank()) continue
                        restoChunk = linha.substringBefore(";").trim().toInt(16)
                        if (restoChunk == 0) throw java.io.EOFException("fim do fluxo")
                    }
                    val k = ent.read(b, 0, minOf(b.size, restoChunk))
                    if (k > 0) { restoChunk -= k; if (restoChunk == 0) { lerLinha(ent) } }
                    k
                } else ent.read(b)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (n < 0) throw java.io.EOFException("o caster fechou a conexão")
            if (n == 0) continue
            ultimo = System.currentTimeMillis(); ultimoDadoMs = ultimo
            bytesRtcm += n
            aoRtcm(b.copyOf(n))
            aoEstado(Estado.RECEBENDO, "recebendo correção")
        }
    }

    /** Consome um "\r\n" logo após o status, se houver, para não repassá-lo ao receptor como se fosse RTCM. */
    private fun pularLinhaEmBranco(ent: BufferedInputStream) {
        ent.mark(2)
        try {
            if (ent.read() == 13 && ent.read() == 10) return
        } catch (_: SocketTimeoutException) { }
        ent.reset()
    }

    private fun enviarGga(out: OutputStream, linha: String) {
        try { synchronized(out) { out.write((linha.trim() + "\r\n").toByteArray(Charsets.US_ASCII)); out.flush() } } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ apoio
    private fun validarEndereco(cfg: Config) {
        if (cfg.host.isBlank()) throw ErroNtrip("informe o endereço do caster")
        if (cfg.porta !in 1..65535) throw ErroNtrip("porta inválida (1 a 65535)")
    }

    /** Rede com internet de verdade (dados móveis, quando o Wi-Fi é o do scanner). */
    private fun redeInternet(): Network? {
        val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        fun ok(n: Network?) = n != null && cm.getNetworkCapabilities(n)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        val padrao = cm.activeNetwork
        if (ok(padrao)) return padrao
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { ok(it) } ?: padrao
    }

    private fun abrir(cfg: Config): Socket {
        val rede = redeInternet()
        try {
            val end = rede?.getByName(cfg.host) ?: java.net.InetAddress.getByName(cfg.host)
            val s = rede?.socketFactory?.createSocket() ?: Socket()
            s.connect(InetSocketAddress(end, cfg.porta), 10000)
            s.soTimeout = 15000
            return s
        } catch (e: java.net.UnknownHostException) {
            throw ErroNtrip("endereço \"${cfg.host}\" não encontrado (confira o nome e a internet do celular)")
        } catch (e: java.net.SocketTimeoutException) {
            throw ErroNtrip("o caster ${cfg.host}:${cfg.porta} não respondeu (porta errada ou sem internet)")
        } catch (e: java.net.ConnectException) {
            throw ErroNtrip("conexão recusada em ${cfg.host}:${cfg.porta} (porta errada?)")
        }
    }

    private fun enviarPedido(out: OutputStream, cfg: Config, mount: String, versao2: Boolean) {
        val sb = StringBuilder()
        sb.append("GET /").append(mount).append(" HTTP/1.").append(if (versao2) "1" else "0").append("\r\n")
        sb.append("Host: ").append(cfg.host).append(":").append(cfg.porta).append("\r\n")
        if (versao2) sb.append("Ntrip-Version: Ntrip/2.0\r\n")
        sb.append("User-Agent: NTRIP ScannerCampo/0.1\r\n")
        sb.append("Accept: */*\r\n")
        if (cfg.usuario.isNotEmpty()) {
            val tok = java.util.Base64.getEncoder().encodeToString("${cfg.usuario}:${cfg.senha}".toByteArray(Charsets.UTF_8))
            sb.append("Authorization: Basic ").append(tok).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); out.flush()
    }

    private fun traduzirStatus(status: String): String = when {
        status.contains(" 401") -> "usuário ou senha recusados pelo caster (401)"
        status.contains(" 403") -> "acesso negado pelo caster (403): conta sem permissão para este mountpoint"
        status.contains(" 404") -> "mountpoint não encontrado (404)"
        status.contains(" 409") -> "conta já está em uso em outra conexão (409)"
        status.contains(" 503") || status.contains(" 500") -> "caster indisponível agora (${status.trim()})"
        else -> "resposta inesperada do caster: ${status.take(80)}"
    }

    /** Lê uma linha (até o fim de linha). Com o socket em timeout curto, insiste até [prazoMs]; null se nada chegou. */
    private fun lerLinha(ent: InputStream, prazoMs: Long = 15000): String? {
        val b = ByteArrayOutputStream()
        val fim = System.currentTimeMillis() + prazoMs
        while (true) {
            val c = try {
                ent.read()
            } catch (_: SocketTimeoutException) {
                if (System.currentTimeMillis() < fim && (ativo.get() || b.size() > 0 || thread == null)) continue
                return if (b.size() > 0) b.toString("ISO-8859-1") else null
            }
            if (c < 0) return if (b.size() > 0) b.toString("ISO-8859-1") else null
            if (c == 10) return b.toString("ISO-8859-1").trimEnd('\r')
            b.write(c)
            if (b.size() > 8192) return b.toString("ISO-8859-1")
        }
    }

    private fun lerLinhaComPrazo(ent: InputStream, prazoMs: Long): String? = lerLinha(ent, prazoMs)

    private fun lerCabecalhos(ent: InputStream): Map<String, String> {
        val m = HashMap<String, String>()
        while (true) {
            val l = lerLinha(ent) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) m[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim().lowercase()
        }
        return m
    }

    private fun lerAteFim(ent: InputStream): ByteArray {
        val o = ByteArrayOutputStream(); val b = ByteArray(8192)
        while (true) {
            val n = try { ent.read(b) } catch (_: SocketTimeoutException) { break }
            if (n < 0) break
            o.write(b, 0, n)
            if (o.size() > 4 && String(o.toByteArray(), Charsets.ISO_8859_1).contains("ENDSOURCETABLE")) break
            if (o.size() > 4 * 1024 * 1024) break
        }
        return o.toByteArray()
    }

    private fun lerChunked(ent: InputStream): ByteArray {
        val o = ByteArrayOutputStream()
        while (true) {
            val linha = lerLinha(ent) ?: break
            val n = linha.substringBefore(";").trim().toIntOrNull(16) ?: break
            if (n == 0) break
            val b = ByteArray(n); var lido = 0
            while (lido < n) { val k = ent.read(b, lido, n - lido); if (k < 0) break; lido += k }
            o.write(b, 0, lido); lerLinha(ent)
        }
        return o.toByteArray()
    }
}
