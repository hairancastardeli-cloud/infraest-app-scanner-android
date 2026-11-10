package br.scanner.campo

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Cadastro de pontos só com o GNSS (RTK) do scanner, como num receptor de topografia.
 *
 * A antena do GS-102G NÃO fica na vertical da ponta do suporte (nem do braço da âncora nem do bastão rosqueado embaixo),
 * e o GNSS sozinho não sabe para onde o scanner está virado. Por isso cada ponto sai de DUAS leituras: a 1ª, e a 2ª com
 * o scanner girado 180° em volta da ponta (sem tirar a ponta do ponto). A antena descreve um círculo em volta da vertical
 * do ponto, então a média das duas leituras cai em cima dela, qualquer que seja o afastamento. A metade da distância
 * entre as duas é o afastamento da antena (confere o suporte), e a diferença de altura entre elas mostra se o scanner
 * estava nivelado igual nas duas.
 *
 * Altura da antena: braço da âncora = 0,253 m da ponta até a antena (do .pro do GS-102G: f8GnssArmRFU sobe 0,04 m e
 * f8AnchorArmRFU desce 0,213 m a partir do centro do scanner); bastão = altura do bastão + rosca → antena (medida pelo
 * usuário). Conferir as duas num marco conhecido antes de usar em serviço.
 */
object PontoGnss {
    enum class Suporte(val rotulo: String, val codigo: String) {
        BRACO("Braço da âncora", "braco"),
        BASTAO("Bastão rosqueado", "bastao");

        companion object { fun de(c: String?) = entries.firstOrNull { it.codigo == c } ?: BRACO }
    }

    const val ALTURA_BRACO = 0.253

    /** Uma época do GGA já em UTM. [h] = altura elipsoidal da antena; [alt] = altitude do GGA (geoide do receptor). */
    data class Epoca(val e: Double, val n: Double, val h: Double, val alt: Double, val qualidade: Int, val satelites: Int)

    class Leitura(val epocas: List<Epoca>, val fuso: Int, val sul: Boolean) {
        val e = epocas.map { it.e }.average()
        val n = epocas.map { it.n }.average()
        val h = epocas.map { it.h }.average()
        val alt = epocas.map { it.alt }.average()
        val sdHz = hypot(dp(epocas.map { it.e }), dp(epocas.map { it.n }))
        val sdV = dp(epocas.map { it.h })
        val soFixo = epocas.all { it.qualidade == 4 }

        private fun dp(v: List<Double>): Double {
            if (v.size < 2) return 0.0
            val m = v.average()
            return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
        }
    }

    /**
     * Junta as épocas de uma leitura. Só entram épocas com posição e com a solução aceita (RTK fixo; flutuante só se
     * [aceitarFlutuante]); a mesma época repetida (mesma hora do GGA) entra uma vez só. Todas no fuso [fuso] (o da 1ª
     * época da 1ª leitura, para as duas leituras ficarem no mesmo fuso).
     */
    class Coletor(val segundos: Int, val aceitarFlutuante: Boolean, private var fuso: Int? = null, private val minEpocas: Int = 5) {
        private val epocas = ArrayList<Epoca>()
        private var ultimaHora = ""
        private var sul = true
        var primeiraMs = 0L
            private set
        var ultimaAceitaMs = 0L
            private set
        var rejeitadas = 0
            private set
        val inicioMs = System.currentTimeMillis()

        val aceitas: Int @Synchronized get() = epocas.size

        @Synchronized fun adicionar(g: Gga, agoraMs: Long = System.currentTimeMillis()): Boolean {
            val lat = g.lat ?: return false
            val lon = g.lon ?: return false
            val alt = g.altitude ?: return false
            if (g.hora.isNotEmpty() && g.hora == ultimaHora) return false
            ultimaHora = g.hora
            if (!(g.qualidade == 4 || (aceitarFlutuante && g.qualidade == 5))) { rejeitadas++; return false }
            val u = Geodesia.utm(lat, lon, fuso)
            fuso = u.fuso; sul = u.sul
            epocas.add(Epoca(u.e, u.n, g.alturaElipsoidal ?: alt, alt, g.qualidade, g.satelites))
            if (primeiraMs == 0L) primeiraMs = agoraMs
            ultimaAceitaMs = agoraMs
            return true
        }

        @Synchronized fun completo(agoraMs: Long = System.currentTimeMillis()) =
            epocas.size >= minEpocas && primeiraMs > 0 && agoraMs - primeiraMs >= segundos * 1000L - 500

        @Synchronized fun leitura(): Leitura? = if (epocas.isEmpty()) null else Leitura(ArrayList(epocas), fuso ?: 23, sul)

        fun fusoAtual(): Int? = fuso
    }

    class Resultado(
        val nome: String, val descricao: String, val suporte: Suporte, val alturaAntena: Double,
        val l1: Leitura, val l2: Leitura, val quando: Date = Date(),
    ) {
        val e = (l1.e + l2.e) / 2
        val n = (l1.n + l2.n) / 2
        val hElip = (l1.h + l2.h) / 2 - alturaAntena
        val altitude = (l1.alt + l2.alt) / 2 - alturaAntena
        val afastamento = hypot(l1.e - l2.e, l1.n - l2.n) / 2
        val difVertical = l2.h - l1.h
        val sdHz = maxOf(l1.sdHz, l2.sdHz)
        val sdV = maxOf(l1.sdV, l2.sdV)
        val soFixo = l1.soFixo && l2.soFixo
        val epocas = l1.epocas.size + l2.epocas.size

        fun avisos(): List<String> {
            val a = ArrayList<String>()
            if (!soFixo) a.add("teve época em RTK FLUTUANTE: precisão bem pior que a do fixo")
            if (sdHz > 0.02) a.add("posição oscilou %.1f cm durante a leitura (antena parada?)".format(sdHz * 100))
            if (sdV > 0.03) a.add("altura oscilou %.1f cm durante a leitura".format(sdV * 100))
            if (afastamento > 0.70) a.add("as leituras estão a %.0f cm uma da outra: mais do que o afastamento da antena. A ponta saiu do ponto?".format(afastamento * 200))
            if (afastamento < 0.05) a.add("as duas leituras quase coincidem: o scanner foi girado 180°?")
            if (abs(difVertical) > 0.05) a.add("as leituras diferem %.1f cm na altura: o scanner não estava nivelado igual nas duas".format(abs(difVertical) * 100))
            return a
        }
    }

    // ------------------------------------------------------------------ arquivo de pontos (CSV)
    /**
     * CSV no formato dos coletores RTK ("Name,Easting,Northing,Elevation,Feature code,…"), que o Gerador/conferência já
     * lê (pontos de controle = descrição começando com PC). Elevation = altitude do GGA (geoide do próprio receptor);
     * a altura elipsoidal vai em coluna própria. O nome do arquivo não pode ter "_gnss" (o Gerador ignora esses).
     */
    val CABECALHO = "Name,Easting,Northing,Elevation,Feature code,Solution State,Zone,h_elipsoidal,Suporte,Altura_antena," +
        "SdHz,SdV,Epocas,Afastamento,DifV_leituras,Data"

    class Linha(val nome: String, val e: Double, val n: Double, val elev: Double, val descricao: String, val estado: String,
                val zona: String, val hElip: Double, val texto: String)

    fun arquivo(pasta: File, obra: String): File = File(pasta, "pontos_rtk_${limpar(obra).ifEmpty { "obra" }}.csv")

    fun limpar(s: String) = s.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun campo(s: String) = s.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun linhaCsv(r: Resultado): String {
        val zona = "${r.l1.fuso}${if (r.l1.sul) "S" else "N"}"
        val data = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(r.quando)
        return String.format(Locale.US, "%s,%.4f,%.4f,%.4f,%s,%s,%s,%.4f,%s,%.3f,%.4f,%.4f,%d,%.3f,%.3f,%s",
            campo(r.nome), r.e, r.n, r.altitude, campo(r.descricao), if (r.soFixo) "FIXED" else "FLOAT", zona, r.hElip,
            r.suporte.codigo, r.alturaAntena, r.sdHz, r.sdV, r.epocas, r.afastamento, r.difVertical, data)
    }

    fun salvar(f: File, r: Resultado) {
        f.parentFile?.mkdirs()
        val novo = !f.exists() || f.length() == 0L
        f.appendText((if (novo) CABECALHO + "\n" else "") + linhaCsv(r) + "\n")
    }

    fun ler(f: File): List<Linha> {
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { l ->
            val c = l.split(',')
            if (c.size < 8) return@mapNotNull null
            val e = c[1].toDoubleOrNull() ?: return@mapNotNull null
            val n = c[2].toDoubleOrNull() ?: return@mapNotNull null
            Linha(c[0], e, n, c[3].toDoubleOrNull() ?: 0.0, c[4], c[5], c[6], c[7].toDoubleOrNull() ?: 0.0, l)
        }
    }

    /** Apaga a linha de índice [i] (na ordem do arquivo) e regrava. */
    fun remover(f: File, i: Int) {
        val l = ler(f)
        if (i !in l.indices) return
        f.writeText(CABECALHO + "\n" + l.filterIndexed { k, _ -> k != i }.joinToString("") { it.texto + "\n" })
    }

    /** Próximo nome: o último nome com número no fim, +1 ("PC3" → "PC4", "12" → "13"); sem nenhum, "1". */
    fun proximoNome(nomes: List<String>): String {
        val ult = nomes.lastOrNull() ?: return "1"
        val m = Regex("^(.*?)(\\d+)$").find(ult) ?: return ult + "2"
        val num = m.groupValues[2]
        return m.groupValues[1] + (num.toLong() + 1).toString().padStart(num.length, '0')
    }
}
