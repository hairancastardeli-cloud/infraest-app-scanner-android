package br.scanner.campo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Semáforo do fim do levantamento: verde / amarelo / vermelho por parte, a partir do que o app viu em campo.
 * Olha a trajetória do SCANNER (não roda o nosso processamento): é um indicador de risco. Os limites são
 * provisórios até serem calibrados com o resultado do processamento de levantamentos reais (o CSV de cada parte vai
 * junto com os dados para isso).
 *
 * Itens:
 *  - eventos de PERDIDO (salto/pose inválida): vermelho;
 *  - tempo com a posição tremendo: amarelo (o aviso que antecedeu o salto no teste de 15/10/2026);
 *  - tempo em ambiente pobre (> 25% do tempo): amarelo;
 *  - fechamento: se a parte termina a menos de 3 m de onde começou (e andou > 20 m), a diferença entre início e fim.
 *    Até 15 cm verde, até 50 cm amarelo, acima vermelho. (No teste 1 de 15/10: 6 cm.)
 *  - GNSS × SLAM: com RTK fixo em pelo menos 20 quadros espalhados por 10 m, ajusta rotação + translação 2D entre a
 *    trajetória do SLAM e a do GNSS e mede a sobra. A antena fica a ~14 cm do centro (braço de alavanca), então isto
 *    só pega erro grosso: máximo até 40 cm verde, até 1 m amarelo, acima vermelho.
 */
object Semaforo {
    enum class Cor { VERDE, AMARELO, VERMELHO }

    class Parte(val nome: String, val amostras: List<MonitorSlam.Amostra>)

    class Item(val cor: Cor, val texto: String)

    class Resultado(val nome: String, val cor: Cor, val itens: List<Item>, val duracaoS: Double, val distancia: Double)

    fun avaliar(p: Parte): Resultado {
        val a = p.amostras
        val itens = ArrayList<Item>()
        if (a.size < 4) return Resultado(p.nome, Cor.VERMELHO, listOf(Item(Cor.VERMELHO, "quase nenhum quadro recebido")), 0.0, 0.0)
        val dur = (a.last().ms - a.first().ms) / 1000.0
        var dist = 0.0
        for (k in 1 until a.size) if (!a[k].perdido) dist += d3(a[k], a[k - 1])

        // perdido: conta os episódios (quadros seguidos contam como um)
        var episodios = 0
        for (k in a.indices) if (a[k].perdido && (k == 0 || !a[k - 1].perdido)) episodios++
        if (episodios > 0) itens.add(Item(Cor.VERMELHO, "o SLAM do scanner se perdeu $episodios vez(es)"))

        fun tempo(f: (MonitorSlam.Amostra) -> Boolean): Double {
            var s = 0.0
            for (k in 1 until a.size) if (f(a[k])) s += (a[k].ms - a[k - 1].ms) / 1000.0
            return s
        }
        val tTremor = tempo { it.tremor }
        if (tTremor > 0) itens.add(Item(Cor.AMARELO, "posição tremendo por %.0f s".format(tTremor)))
        val tPobre = tempo { it.pobre }
        if (dur > 0 && tPobre / dur > 0.25) itens.add(Item(Cor.AMARELO, "ambiente pobre em %.0f%% do tempo".format(100 * tPobre / dur)))
        else if (tPobre > 0) itens.add(Item(Cor.VERDE, "ambiente pobre por %.0f s".format(tPobre)))

        // fechamento (só se voltou perto do início)
        val ini = a.first(); val fim = a.last()
        val gap = d3(ini, fim)
        if (episodios == 0 && dist > 20 && gap < 3.0) {
            val c = if (gap <= 0.15) Cor.VERDE else if (gap <= 0.5) Cor.AMARELO else Cor.VERMELHO
            itens.add(Item(c, "voltou ao início: diferença de %.0f cm em %.0f m".format(gap * 100, dist)))
        } else if (episodios == 0 && dist > 20) {
            itens.add(Item(Cor.VERDE, "não terminou no ponto de partida (sem conferência de fechamento)"))
        }

        gnssSlam(a)?.let { (rms, max, n) ->
            val c = if (max <= 0.4) Cor.VERDE else if (max <= 1.0) Cor.AMARELO else Cor.VERMELHO
            itens.add(Item(c, "GNSS × SLAM (%d posições RTK fixo): média %.0f cm, máxima %.0f cm".format(n, rms * 100, max * 100)))
        }

        if (itens.none { it.cor != Cor.VERDE }) itens.add(0, Item(Cor.VERDE, "nenhum sinal de problema"))
        val cor = when {
            itens.any { it.cor == Cor.VERMELHO } -> Cor.VERMELHO
            itens.any { it.cor == Cor.AMARELO } -> Cor.AMARELO
            else -> Cor.VERDE
        }
        return Resultado(p.nome, cor, itens, dur, dist)
    }

    /** Rotação + translação 2D (sem escala) do SLAM para o GNSS; devolve (rms, máximo, n) ou null se não dá. */
    fun gnssSlam(a: List<MonitorSlam.Amostra>): Triple<Double, Double, Int>? {
        val v = a.filter { !it.e.isNaN() && !it.n.isNaN() && !it.perdido }
        if (v.size < 20) return null
        val mx = v.sumOf { it.x } / v.size; val my = v.sumOf { it.y } / v.size
        val me = v.sumOf { it.e } / v.size; val mn = v.sumOf { it.n } / v.size
        var ext = 0.0
        for (s in v) ext = maxOf(ext, sqrt((s.e - me) * (s.e - me) + (s.n - mn) * (s.n - mn)))
        if (ext < 5.0) return null                                  // espalhado por pelo menos ~10 m
        var sxx = 0.0; var sxy = 0.0
        for (s in v) {
            val x = s.x - mx; val y = s.y - my; val e = s.e - me; val n = s.n - mn
            sxx += x * e + y * n; sxy += x * n - y * e
        }
        val th = atan2(sxy, sxx); val c = cos(th); val sn = sin(th)
        var soma = 0.0; var mxErr = 0.0
        for (s in v) {
            val x = s.x - mx; val y = s.y - my
            val de = c * x - sn * y - (s.e - me); val dn = sn * x + c * y - (s.n - mn)
            val r = sqrt(de * de + dn * dn)
            soma += r * r; mxErr = maxOf(mxErr, r)
        }
        return Triple(sqrt(soma / v.size), mxErr, v.size)
    }

    fun texto(rs: List<Resultado>): String = buildString {
        for (r in rs) {
            append("${r.nome}: ${nomeCor(r.cor)}  (%.0f min, %.0f m)\n".format(r.duracaoS / 60, r.distancia))
            for (i in r.itens) append("  [${nomeCor(i.cor)}] ${i.texto}\n")
        }
    }

    fun nomeCor(c: Cor) = when (c) { Cor.VERDE -> "VERDE"; Cor.AMARELO -> "AMARELO"; Cor.VERMELHO -> "VERMELHO" }

    private fun d3(p: MonitorSlam.Amostra, q: MonitorSlam.Amostra): Double {
        val dx = p.x - q.x; val dy = p.y - q.y; val dz = p.z - q.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
