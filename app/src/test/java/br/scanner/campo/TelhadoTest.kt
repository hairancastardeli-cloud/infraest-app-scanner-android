package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class TelhadoTest {
    /** Galpão 20 × 20 m: piso z=0, telhado em arco (7 a 9 m) com terças logo abaixo, paredes, uma treliça e terreno fora. */
    private fun galpao(): Triple<FloatArray, Int, Int> {
        val p = ArrayList<Float>()
        fun add(x: Double, y: Double, z: Double) { p.add(x.toFloat()); p.add(y.toFloat()); p.add(z.toFloat()) }
        fun arco(x: Double) = 7.0 + 2.0 * sin(Math.PI * x / 20.0)
        var piso = 0; var telhado = 0
        var x = 0.05
        while (x < 20) {
            var y = 0.05
            while (y < 20) {
                add(x, y, 0.0); piso++                                         // piso
                add(x, y, arco(x)); add(x, y, arco(x) - 0.15); telhado += 2    // telha + terça
                y += 0.2
            }
            x += 0.2
        }
        // paredes (contínuas do piso ao telhado) em x = 0 e x = 20
        for (xw in listOf(0.02, 19.98)) { var y = 0.05; while (y < 20) { var z = 0.1; while (z < arco(xw)) { add(xw, y, z); z += 0.1 }; y += 0.2 } }
        // treliça em y = 10: membros contínuos de 5 m até o telhado
        x = 0.05; while (x < 20) { var z = 5.0; while (z < arco(x) - 0.2) { add(x, 10.05, z); z += 0.1 }; x += 0.2 }
        // terreno fora do galpão (nada em cima)
        x = 22.05; while (x < 26) { var y = 0.05; while (y < 20) { add(x, y, -0.2); y += 0.2 }; x += 0.2 }
        return Triple(p.toFloatArray(), piso, telhado)
    }

    @Test fun tiraOTelhadoEmArcoEMantemPisoParedesETerreno() {
        val (xyz, piso, telhado) = galpao()
        val r = Telhado.remover(xyz)
        val z = (0 until r.xyz.size / 3).map { r.xyz[3 * it + 2] }
        val zx = (0 until r.xyz.size / 3).map { r.xyz[3 * it] to r.xyz[3 * it + 2] }
        // piso inteiro e terreno de fora ficam
        assertEquals(piso, z.count { it == 0f })
        assertTrue(zx.count { it.first > 21 } > 0)
        // quase nada do telhado sobra no meio do galpão (longe das paredes)
        val sobraTelhado = zx.count { (x, zz) -> x in 1.0..19.0 && zz > 6.6 && zz >= 7.0 + 2.0 * sin(Math.PI * x / 20.0) - 0.3 }
        assertTrue("sobrou $sobraTelhado de $telhado pontos de telhado", sobraTelhado < telhado * 0.03)
        // a treliça (abaixo do telhado) fica
        assertTrue(zx.count { it.second in 5.0..6.0 } > 0)
        assertTrue(r.removidos >= telhado * 0.9)
    }

    @Test fun nuvemSemTelhadoNaoMuda() {
        val xyz = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0.1f, 5f, 5f, -0.2f)
        assertEquals(0, Telhado.remover(xyz).removidos)
    }
}
