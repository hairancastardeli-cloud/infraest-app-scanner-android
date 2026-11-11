package br.scanner.campo

import kotlin.math.abs
import kotlin.math.floor

/**
 * "Sem telhado" na prévia 3D: em cada célula de [celula] m (planta), a camada mais alta (até [espessura] m de espessura:
 * telha + terças) sai quando tem um VÃO vazio de pelo menos [vao] m embaixo — telhado, laje, forro. Decisão local, então
 * serve para telhado em arco ou com caimento. Ficam o piso, as paredes (contínuas, sem vão) e o terreno de fora (nada
 * embaixo). Onde uma treliça ou um pilar encosta no telhado não há vão; aí a célula sai se pelo menos [vizinhos] das 8
 * vizinhas tiveram o telhado tirado na mesma altura (±[tolerancia] m), para não sobrar faixa de telhado.
 * z é a altura (o mesmo eixo da cor do visualizador).
 */
object Telhado {
    class Resultado(val xyz: FloatArray, val removidos: Int)

    fun remover(xyz: FloatArray, celula: Float = 0.5f, espessura: Float = 0.6f, vao: Float = 1.0f,
                vizinhos: Int = 3, tolerancia: Float = 0.5f): Resultado {
        val n = xyz.size / 3
        if (n == 0) return Resultado(xyz, 0)
        fun chave(ix: Int, iy: Int) = (ix.toLong() shl 32) or (iy.toLong() and 0xffffffffL)

        // pontos por célula
        val celulas = HashMap<Long, IntArrayLista>()
        for (i in 0 until n) {
            val k = chave(floor(xyz[3 * i] / celula).toInt(), floor(xyz[3 * i + 1] / celula).toInt())
            celulas.getOrPut(k) { IntArrayLista() }.add(i)
        }

        // 1ª passada: topo de cada célula e se há vão embaixo da camada de cima
        class Topo(val zTopo: Float, val zBase: Float, val temVao: Boolean, val temBaixo: Boolean)
        val topos = HashMap<Long, Topo>(celulas.size * 2)
        for ((k, lista) in celulas) {
            val zs = FloatArray(lista.n) { xyz[3 * lista.v[it] + 2] }
            zs.sort()
            val zTopo = zs[zs.size - 1]
            var j = zs.size - 1
            while (j > 0 && zs[j - 1] >= zTopo - espessura) j--
            val zBase = zs[j]
            val temBaixo = j > 0
            topos[k] = Topo(zTopo, zBase, temBaixo && zBase - zs[j - 1] >= vao, temBaixo)
        }

        // 2ª passada: células sem vão (treliça, pilar) com o telhado tirado em volta, na mesma altura
        val tirar = HashSet<Long>()
        for ((k, t) in topos) if (t.temVao) tirar.add(k)
        for ((k, t) in topos) {
            if (k in tirar || !t.temBaixo) continue
            val ix = (k shr 32).toInt(); val iy = k.toInt()
            var c = 0
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val kv = chave(ix + dx, iy + dy)
                val tv = topos[kv] ?: continue
                if (tv.temVao && abs(tv.zTopo - t.zTopo) <= tolerancia) c++
            }
            if (c >= vizinhos) tirar.add(k)
        }

        // monta a nuvem sem a camada de cima das células marcadas
        val manter = BooleanArray(n) { true }
        var removidos = 0
        for (k in tirar) {
            val t = topos[k] ?: continue
            val lista = celulas[k] ?: continue
            for (a in 0 until lista.n) {
                val i = lista.v[a]
                if (xyz[3 * i + 2] >= t.zBase) { manter[i] = false; removidos++ }
            }
        }
        val saida = FloatArray((n - removidos) * 3)
        var o = 0
        for (i in 0 until n) if (manter[i]) { saida[o++] = xyz[3 * i]; saida[o++] = xyz[3 * i + 1]; saida[o++] = xyz[3 * i + 2] }
        return Resultado(saida, removidos)
    }

    /** Lista de inteiros sem caixas (Integer) para 400 mil pontos não pesarem. */
    class IntArrayLista {
        var v = IntArray(8); var n = 0
        fun add(x: Int) { if (n == v.size) v = v.copyOf(n * 2); v[n++] = x }
    }
}
