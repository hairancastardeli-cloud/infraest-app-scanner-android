package br.scanner.campo

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O que o scanner realmente manda, para diagnóstico: por tipo de mensagem, quantas chegaram, o tamanho e os primeiros
 * bytes da primeira e da última; mais as últimas linhas GGA recebidas e se foram lidas. Fica em
 * Android/data/br.scanner.campo/files/logs_slam/diagnostico_scanner.txt (regravado a cada 10 s).
 */
class Diagnostico {
    private class Tipo(var n: Long = 0, var primeira: String = "", var ultima: String = "", var tamanhos: MutableSet<Int> = sortedSetOf())

    private val tipos = java.util.TreeMap<String, Tipo>()
    private val ultimosGga = ArrayDeque<String>()
    private val notas = ArrayDeque<String>()
    private var gga = 0L
    private var ggaLidos = 0L
    private var ultimaGravacao = 0L
    private val inicio = System.currentTimeMillis()

    private fun hex(b: ByteArray, n: Int = 96) = b.take(n).joinToString(" ") { "%02X".format(it) } + if (b.size > n) " …" else ""

    @Synchronized fun mensagem(m: Protocolo.Mensagem) {
        val chave = "%s [%s]".format(m.tipo.name, m.codigo.joinToString(" ") { "%02X".format(it) })
        val t = tipos.getOrPut(chave) { Tipo() }
        t.n++
        if (t.tamanhos.size < 6) t.tamanhos.add(m.dados.size)
        val amostra = "%d bytes: %s".format(m.dados.size, hex(m.dados))
        if (t.n == 1L) t.primeira = amostra
        t.ultima = amostra
    }

    @Synchronized fun gga(texto: String, lido: Boolean) {
        gga++; if (lido) ggaLidos++
        ultimosGga.addLast((if (lido) "OK    " else "FALHOU") + " " + texto.trim().replace("\r", "\\r").replace("\n", "\\n").take(160))
        while (ultimosGga.size > 8) ultimosGga.removeFirst()
    }

    @Synchronized fun nota(s: String) { if (notas.lastOrNull() != s) notas.addLast(s); while (notas.size > 6) notas.removeFirst() }

    @Synchronized fun gravar(pasta: File, agora: Long = System.currentTimeMillis()) {
        if (agora - ultimaGravacao < 10_000) return
        ultimaGravacao = agora
        try {
            pasta.mkdirs()
            val f = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
            File(pasta, "diagnostico_scanner.txt").writeText(buildString {
                append("Scanner Campo — diagnóstico das mensagens do scanner\n")
                append("início ${f.format(Date(inicio))} | agora ${f.format(Date(agora))}\n\n")
                append("Linhas GGA recebidas: $gga (lidas com sucesso: $ggaLidos)\n")
                ultimosGga.forEach { append("  $it\n") }
                append("\nTipos de mensagem:\n")
                for ((k, t) in tipos) {
                    append("  %-34s n=%d tamanhos=%s\n    primeira: %s\n    última:   %s\n".format(k, t.n, t.tamanhos, t.primeira, t.ultima))
                }
                if (notas.isNotEmpty()) append("\nNotas:\n" + notas.joinToString("\n") { "  $it" } + "\n")
            })
        } catch (_: Exception) { }
    }
}
