package br.scanner.campo

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prévia da nuvem de um levantamento: junta os pontos dos quadros que o scanner manda ao celular (a nuvem reduzida
 * do SLAM do aparelho), um ponto por voxel de [voxel] m, até [maxPontos]. Fica gravada em logs_slam/nuvem_<nome>.bin
 * para abrir depois no visualizador 3D. É a nuvem do SCANNER em tempo real (não o nosso processamento): serve para
 * ver se cobriu tudo e se o traçado faz sentido.
 *
 * Referencial dos pontos: o app 0.6 desenha os pontos do quadro junto com a pose (mesmo referencial). Por garantia,
 * nos primeiros quadros com o scanner a mais de 5 m da origem ele confere: se o centro dos pontos fica perto da
 * origem e longe da pose, os pontos vêm no referencial do sensor e são levados ao mundo pela pose ([local]).
 *
 * Arquivo: "NUV1" + int32 n + n × (x, y, z) float32, little-endian.
 */
class NuvemAcumulada(val voxel: Double = 0.10, val maxPontos: Int = 2_000_000) {
    private val chaves = HashSet<Long>()
    private var xyz = FloatArray(3 * 65536)
    @Volatile var n = 0
        private set
    @Volatile var versao = 0
        private set
    /** null = ainda decidindo; true = pontos no referencial do sensor; false = já no referencial do mundo. */
    var local: Boolean? = null
        private set
    private var votosLocal = 0
    private var votosMundo = 0

    @Synchronized fun limpar() { chaves.clear(); n = 0; versao++; local = null; votosLocal = 0; votosMundo = 0 }

    @Synchronized fun adicionar(q: Protocolo.QuadroNuvem) {
        val p = q.pose
        if (p.any { it.isNaN() || it.isInfinite() } || q.pontos.size < 3) return
        decidirReferencial(q)
        val loc = local == true
        val cy = cos(Math.toRadians(p[5].toDouble())); val sy = sin(Math.toRadians(p[5].toDouble()))
        val cp = cos(Math.toRadians(p[3].toDouble())); val sp = sin(Math.toRadians(p[3].toDouble()))
        val cr = cos(Math.toRadians(p[4].toDouble())); val sr = sin(Math.toRadians(p[4].toDouble()))
        var i = 0
        while (i + 2 < q.pontos.size && n < maxPontos) {
            var x = q.pontos[i].toDouble(); var y = q.pontos[i + 1].toDouble(); var z = q.pontos[i + 2].toDouble()
            i += 3
            if (x.isNaN() || y.isNaN() || z.isNaN()) continue
            if (loc) {                                                // Rz(yaw) · Ry(pitch) · Rx(roll) + posição
                val y1 = cr * y - sr * z; val z1 = sr * y + cr * z
                val x2 = cp * x + sp * z1; val z2 = -sp * x + cp * z1
                val x3 = cy * x2 - sy * y1; val y3 = sy * x2 + cy * y1
                x = x3 + p[0]; y = y3 + p[1]; z = z2 + p[2]
            }
            val k = chave(x, y, z)
            if (!chaves.add(k)) continue
            if (3 * n + 3 > xyz.size) xyz = xyz.copyOf(minOf(xyz.size * 2, 3 * maxPontos))
            xyz[3 * n] = x.toFloat(); xyz[3 * n + 1] = y.toFloat(); xyz[3 * n + 2] = z.toFloat()
            n++
        }
        versao++
    }

    private fun decidirReferencial(q: Protocolo.QuadroNuvem) {
        if (local != null) return
        val p = q.pose
        val dPose = sqrt((p[0] * p[0] + p[1] * p[1]).toDouble())
        if (dPose < 5.0) return
        var cx = 0.0; var cy = 0.0; var m = 0
        var i = 0
        while (i + 2 < q.pontos.size) { cx += q.pontos[i]; cy += q.pontos[i + 1]; m++; i += 3 }
        if (m == 0) return
        cx /= m; cy /= m
        val aOrigem = sqrt(cx * cx + cy * cy)
        val aPose = sqrt((cx - p[0]) * (cx - p[0]) + (cy - p[1]) * (cy - p[1]))
        if (aOrigem < aPose) votosLocal++ else votosMundo++
        if (votosLocal + votosMundo >= 5) {
            local = votosLocal > votosMundo
            if (local == true) { chaves.clear(); n = 0 }              // o que entrou antes estava no lugar errado
        }
    }

    private fun chave(x: Double, y: Double, z: Double): Long {
        val ix = floor(x / voxel).toLong() and 0x1FFFFF
        val iy = floor(y / voxel).toLong() and 0x1FFFFF
        val iz = floor(z / voxel).toLong() and 0x1FFFFF
        return (ix shl 42) or (iy shl 21) or iz
    }

    /** Cópia dos pontos (x, y, z intercalados). */
    @Synchronized fun copia(): FloatArray = xyz.copyOf(3 * n)

    @Synchronized fun gravar(f: File) {
        val tmp = File(f.path + ".tmp")
        BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { out ->
            val cab = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            cab.put("NUV1".toByteArray(Charsets.US_ASCII)).putInt(n)
            out.write(cab.array())
            val bb = ByteBuffer.allocate(12 * 4096).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < n) {
                bb.clear()
                val fim = minOf(n, i + 4096)
                for (k in i until fim) bb.putFloat(xyz[3 * k]).putFloat(xyz[3 * k + 1]).putFloat(xyz[3 * k + 2])
                out.write(bb.array(), 0, bb.position())
                i = fim
            }
        }
        if (f.exists()) f.delete()
        tmp.renameTo(f)
    }

    companion object {
        fun ler(f: File): FloatArray {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { inp ->
                val cab = ByteArray(8); inp.readFully(cab)
                val bb = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN)
                val magia = String(cab, 0, 4, Charsets.US_ASCII)
                require(magia == "NUV1") { "arquivo de nuvem inválido" }
                bb.position(4)
                val n = bb.int
                require(n in 0..50_000_000) { "arquivo de nuvem inválido" }
                val dados = ByteArray(12 * n); inp.readFully(dados)
                val fb = ByteBuffer.wrap(dados).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val out = FloatArray(3 * n); fb.get(out)
                return out
            }
        }

        /** Nº de pontos do arquivo sem ler tudo. */
        fun contar(f: File): Int = try {
            DataInputStream(FileInputStream(f)).use { inp ->
                val cab = ByteArray(8); inp.readFully(cab)
                ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            }
        } catch (_: Exception) { -1 }
    }
}
