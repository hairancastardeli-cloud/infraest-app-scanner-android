package br.scanner.campo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NuvemAcumuladaTest {
    private fun q(pose: FloatArray, pts: FloatArray) = Protocolo.QuadroNuvem(pts.size / 3, 16, 0L, 10, pose, pts)

    @Test fun umPontoPorVoxelEGravaELe() {
        val nv = NuvemAcumulada(voxel = 0.1)
        // dois pontos no mesmo voxel de 10 cm + um em outro
        nv.adicionar(q(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f), floatArrayOf(1.01f, 2.01f, 0.51f, 1.02f, 2.03f, 0.52f, 3f, 3f, 3f)))
        assertEquals(2, nv.n)
        val f = File(Files.createTempDirectory("nuv").toFile(), "nuvem_t.bin")
        nv.gravar(f)
        assertEquals(2, NuvemAcumulada.contar(f))
        assertArrayEquals(nv.copia(), NuvemAcumulada.ler(f), 0f)
    }

    @Test fun pontosNoMundoFicamComoEstao() {
        val nv = NuvemAcumulada()
        for (i in 0 until 8) {
            val x = 10f + i
            nv.adicionar(q(floatArrayOf(x, 0f, 0f, 0f, 0f, 90f), floatArrayOf(x + 2f, 1f, 0f, x - 2f, -1f, 1f)))  // em volta da pose
        }
        assertEquals(false, nv.local)
        val c = nv.copia()
        assertTrue(c.any { it > 15f })
    }

    @Test fun pontosDoSensorSaoLevadosPelaPose() {
        val nv = NuvemAcumulada()
        for (i in 0 until 8) {
            val x = 10f + i
            // pontos em volta da origem (referencial do sensor); yaw 90°: o ponto (1, 0) do sensor vai para (x, 1) no mundo
            nv.adicionar(q(floatArrayOf(x, 0f, 0f, 0f, 0f, 90f), floatArrayOf(1f, 0f, 0f, -1f, 0f, 0.5f)))
        }
        assertEquals(true, nv.local)
        val c = nv.copia()
        val achou = (0 until c.size / 3).any { Math.abs(c[3 * it] - 17f) < 0.01 && Math.abs(c[3 * it + 1] - 1f) < 0.01 }
        assertTrue(achou)
    }
}
