package br.scanner.campo

/** Leitura da linha NMEA GGA que o scanner manda (posição do receptor GNSS e qualidade da solução). */
data class Gga(val qualidade: Int, val satelites: Int, val hdop: Double?, val idadeCorrecao: Double?, val altitude: Double?) {

    fun nomeSolucao(): String = when (qualidade) {
        0 -> "Sem posição"; 1 -> "Autônomo"; 2 -> "DGPS"; 4 -> "RTK fixo"; 5 -> "RTK flutuante"; 6 -> "Estimado"; else -> "Qualidade $qualidade"
    }

    fun resumo(): String {
        val sol = when (qualidade) {
            0 -> "sem posição"
            1 -> "autônomo (sem correção)"
            2 -> "DGPS"
            4 -> "RTK FIXO"
            5 -> "RTK flutuante"
            6 -> "estimado"
            else -> "qualidade $qualidade"
        }
        val idade = idadeCorrecao?.let { " · correção com %.0f s".format(it) } ?: ""
        val h = hdop?.let { " · HDOP %.1f".format(it) } ?: ""
        return "GNSS: $sol · $satelites satélites$h$idade"
    }

    companion object {
        fun ler(texto: String): Gga? {
            val i0 = texto.indexOf("GGA,").takeIf { it >= 0 } ?: return null
            val ini = texto.lastIndexOf('$', i0).takeIf { it >= 0 } ?: return null              // lixo antes do "$" é ignorado
            val linha = texto.substring(ini).lineSequence().first().trim { it <= ' ' }
            if (!checksumOk(linha)) return null
            val c = linha.substringBefore('*').split(',')
            if (c.size < 14) return null
            return Gga(
                qualidade = c[6].toIntOrNull() ?: 0, satelites = c[7].toIntOrNull() ?: 0, hdop = c[8].toDoubleOrNull(),
                altitude = c[9].toDoubleOrNull(), idadeCorrecao = c[13].toDoubleOrNull(),
            )
        }

        private fun checksumOk(l: String): Boolean {
            val i = l.indexOf('*')
            if (i < 0) return true
            val esperado = l.substring(i + 1).take(2).toIntOrNull(16) ?: return false
            var x = 0
            for (ch in l.substring(1, i)) x = x xor ch.code
            return x == esperado
        }
    }
}


/** Desvios padrão de posição (m) da sentença NMEA GST, se o receptor a mandar. */
data class Gst(val sdLat: Double, val sdLon: Double, val sdAlt: Double?) {
    val horizontal: Double get() = Math.sqrt(sdLat * sdLat + sdLon * sdLon)

    companion object {
        fun ler(texto: String): Gst? {
            val i0 = texto.indexOf("GST,").takeIf { it >= 0 } ?: return null
            val ini = texto.lastIndexOf('$', i0).takeIf { it >= 0 } ?: return null
            val linha = texto.substring(ini).lineSequence().first().trim { it <= ' ' }
            val c = linha.substringBefore('*').split(',')
            if (c.size < 9) return null
            val la = c[6].toDoubleOrNull() ?: return null
            val lo = c[7].toDoubleOrNull() ?: return null
            return Gst(la, lo, c[8].toDoubleOrNull())
        }
    }
}

/** Precisão a mostrar: medida (GST) quando o receptor manda; senão a faixa típica do tipo de solução (estimativa). */
data class Precisao(val valor: String, val sub: String, val nivel: Int) {         // nivel: 2 ok, 1 atenção, 0 ruim, -1 sem dado
    companion object {
        fun de(g: Gga, gst: Gst?): Precisao {
            if (gst != null) {
                val h = gst.horizontal
                val v = if (h < 1.0) "±%.1f cm".format(h * 100) else "±%.2f m".format(h)
                return Precisao(v, "medida pelo receptor (GST)", if (h < 0.05) 2 else if (h < 0.5) 1 else 0)
            }
            return when (g.qualidade) {
                4 -> Precisao("≈ 2 cm", "típica do RTK fixo (estimada)", 2)
                5 -> Precisao("≈ 20–50 cm", "típica do flutuante (estimada)", 1)
                2 -> Precisao("≈ 0,5–1 m", "típica do DGPS (estimada)", 1)
                1 -> Precisao("≈ 2–5 m", "autônomo, sem correção (estimada)", 0)
                else -> Precisao("—", "sem posição", -1)
            }
        }
    }
}
