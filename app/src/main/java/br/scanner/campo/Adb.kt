package br.scanner.campo

import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.SocketFactory

/**
 * Cliente adb mínimo (protocolo do adbd por TCP, sem bibliotecas) para rodar comandos de shell no scanner pelo Wi-Fi
 * (porta 5555, aberta no GS-102G). Só CNXN / OPEN "shell:" / WRTE / OKAY / CLSE. Se o scanner pedir AUTH (chave RSA), para
 * e avisa: não há como aceitar a chave sem tela no aparelho.
 *
 * Mensagem: 6 inteiros little-endian (comando, arg0, arg1, tamanho, soma dos bytes, comando xor 0xFFFFFFFF) + dados.
 */
class Adb(private val fabrica: SocketFactory, private val ip: String, private val porta: Int = 5555) : AutoCloseable {
    companion object {
        const val CNXN = 0x4e584e43
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val AUTH = 0x48545541
        const val VERSAO = 0x01000000

        fun montar(cmd: Int, a0: Int, a1: Int, dados: ByteArray = ByteArray(0)): ByteArray {
            var soma = 0
            for (b in dados) soma += b.toInt() and 0xff
            return ByteBuffer.allocate(24 + dados.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cmd).putInt(a0).putInt(a1).putInt(dados.size).putInt(soma).putInt(cmd xor -1).put(dados).array()
        }
    }

    class Msg(val cmd: Int, val a0: Int, val a1: Int, val dados: ByteArray)

    class ErroAdb(msg: String) : Exception(msg)

    private var socket: Socket? = null
    private lateinit var ent: DataInputStream
    private lateinit var sai: OutputStream
    private var proximoId = 1
    var banner = ""
        private set

    fun conectar(prazoMs: Int = 4000) {
        val s = fabrica.createSocket()
        s.connect(InetSocketAddress(ip, porta), prazoMs)
        s.soTimeout = 15_000
        socket = s; ent = DataInputStream(s.getInputStream()); sai = s.getOutputStream()
        enviar(CNXN, VERSAO, 256 * 1024, "host::\u0000".toByteArray())
        val r = ler()
        when (r.cmd) {
            CNXN -> banner = String(r.dados, Charsets.ISO_8859_1).trimEnd('\u0000')
            AUTH -> throw ErroAdb("o adb do scanner pede autorização por chave (AUTH): não dá para aceitar sem tela no aparelho")
            else -> throw ErroAdb("resposta inesperada do adb: %08x".format(r.cmd))
        }
    }

    /** Roda [comando] no shell do scanner e devolve a saída (até [prazoMs] ou [limite] bytes). */
    fun shell(comando: String, prazoMs: Long = 20_000, limite: Int = 200_000): String {
        val meu = proximoId++
        enviar(OPEN, meu, 0, "shell:$comando\u0000".toByteArray())
        val saida = StringBuilder()
        var remoto = 0
        val fim = System.currentTimeMillis() + prazoMs
        while (System.currentTimeMillis() < fim) {
            val m = ler()
            if (m.a1 != meu) continue                                    // de outro canal (não deve acontecer)
            when (m.cmd) {
                OKAY -> remoto = m.a0
                WRTE -> {
                    remoto = m.a0
                    if (saida.length < limite) saida.append(String(m.dados, Charsets.UTF_8))
                    enviar(OKAY, meu, remoto)
                }
                CLSE -> { enviar(CLSE, meu, remoto); return saida.toString() }
            }
        }
        if (remoto != 0) enviar(CLSE, meu, remoto)
        return saida.append("\n[prazo de ${prazoMs / 1000} s esgotado]").toString()
    }

    private fun enviar(cmd: Int, a0: Int, a1: Int, dados: ByteArray = ByteArray(0)) {
        sai.write(montar(cmd, a0, a1, dados)); sai.flush()
    }

    private fun ler(): Msg {
        val cab = ByteArray(24)
        ent.readFully(cab)
        val b = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val n = b.int
        if (n < 0 || n > 1024 * 1024) throw EOFException("mensagem adb com tamanho $n")
        val d = ByteArray(n); ent.readFully(d)
        return Msg(cmd, a0, a1, d)
    }

    override fun close() { try { socket?.close() } catch (_: Exception) { } }
}
