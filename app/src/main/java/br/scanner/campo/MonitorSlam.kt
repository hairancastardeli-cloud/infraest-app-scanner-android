package br.scanner.campo

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Vigia do SLAM a partir dos quadros de nuvem que o scanner manda (pose por quadro, ~2 por segundo). O FireEye recebe a
 * mesma pose e não avisa nada; o código de estado do quadro também não muda quando o SLAM se perde (ficou em 10 do
 * início ao fim no teste de 15/10/2026, inclusive no salto). Daí os alarmes daqui:
 *
 *  ATENÇÃO (prestes a se perder):
 *   - POSIÇÃO TREMENDO: a pose vai e volta de um quadro para o outro (segunda diferença da posição, mediana de 4
 *     quadros, acima de [tremorAtencao] m). Andando normal fica abaixo de 0,26 m; no teste de 15/10 passou de 0,6 m
 *     ~10 s antes do salto. É o aviso mais confiável: repete o apito.
 *   - AMBIENTE POBRE: pontos por quadro (mediana de 8 quadros = 4 s) abaixo de [pobreFracao] do normal DESTE
 *     levantamento por mais de [pobreSegundos] s. A comparação é com o normal do levantamento, não com os últimos
 *     segundos (no teste de 15/10 os pontos caíram aos poucos e o alarme antigo, que comparava com a média recente,
 *     não percebeu). Sozinho não quer dizer que vai se perder (o teste 1 passou por trecho assim e fechou a 6 cm): apita
 *     só ao entrar.
 *   - velocidade acima de [vAtencao] m/s, ou salto (3× a mediana recente);
 *   - quadros atrasados; código de estado diferente do normal.
 *  PERDIDO:
 *   - deslocamento acima de [vPerdido] m/s, pose inválida ou pulo vertical > 3 m ([Situacao.perdidoPose] = true:
 *     é o caso em que o app pausa sozinho);
 *   - nenhum quadro há mais de [atrasoPerdidoS] s (pode ser só o Wi-Fi: não pausa sozinho).
 *
 * Todo quadro vai para um CSV e para [amostras] (o resumo do fim do levantamento sai delas).
 */
class MonitorSlam(private val pastaLog: File) {
    enum class Nivel { PARADO, OK, ATENCAO, PERDIDO }

    data class Situacao(
        val nivel: Nivel, val motivo: String, val velocidade: Double, val passo: Double,
        val pontos: Int, val quadrosPorS: Double, val estado: Int, val distanciaTotal: Double,
        val repetir: Boolean = false,           // ATENÇÃO que repete o apito (tremor, salto, rapidez); senão apita só ao entrar
        val perdidoPose: Boolean = false,       // PERDIDO pela própria pose (não por falta de quadros)
    )

    /** Um quadro, guardado para o resumo do fim (semáforo) e para achar o ponto de retomada. */
    class Amostra(
        val ms: Long, val x: Double, val y: Double, val z: Double, val pontos: Int,
        val tremor: Boolean, val pobre: Boolean, val perdido: Boolean, val atencao: Boolean,
        val e: Double, val n: Double,           // GNSS RTK fixo em metros locais (NaN se não havia)
    )

    var vAtencao = 2.5
    var vPerdido = 5.0
    var atrasoAtencaoS = 2.5
    var atrasoPerdidoS = 6.0
    var tremorAtencao = 0.35
    var pobreFracao = 0.25
    var pobreSegundos = 6.0

    private var ultimaPose: FloatArray? = null
    private var ultimoMs = 0L
    private var primeiroMs = 0L
    private val velocidades = ArrayDeque<Double>()
    private val poses = ArrayDeque<DoubleArray>()          // últimas 3 posições (para a segunda diferença)
    private val tremores = ArrayDeque<Double>()            // últimas 4 segundas diferenças
    private val janelaPts = ArrayDeque<Int>()               // últimos 8 quadros
    private val historicoPts = ArrayList<Double>()          // mediana de cada janela de 8 quadros com movimento
    private var quadrosNaJanela = 0
    private var pobreDesdeMs = 0L
    private var estavaPobre = false
    private var estadoNormal: Int? = null
    private var quadros = 0
    private var distancia = 0.0
    private var csv: FileWriter? = null
    private val _amostras = ArrayList<Amostra>()
    val amostras: List<Amostra> get() = synchronized(_amostras) { ArrayList(_amostras) }

    // GNSS: última posição RTK fixa, em metros locais a partir da primeira recebida
    private var lat0 = Double.NaN; private var lon0 = Double.NaN
    private var gnssE = Double.NaN; private var gnssN = Double.NaN; private var gnssMs = 0L

    val totalQuadros: Int get() = quadros
    var arquivoLog: File? = null
        private set
    @Volatile var rodando = false
        private set
    @Volatile var situacao = Situacao(Nivel.PARADO, "", 0.0, 0.0, 0, 0.0, 0, 0.0)
        private set

    /** Pontos "normais" deste levantamento (mediana das janelas com movimento), ou null se ainda não deu para saber. */
    val pontosNormais: Double? get() = if (historicoPts.size >= 3) mediana(historicoPts) else null

    fun comecar(nome: String) {
        parar()
        ultimaPose = null; ultimoMs = 0; primeiroMs = 0; quadros = 0; distancia = 0.0
        velocidades.clear(); poses.clear(); tremores.clear(); janelaPts.clear(); historicoPts.clear(); quadrosNaJanela = 0
        pobreDesdeMs = 0; estavaPobre = false; estadoNormal = null
        synchronized(_amostras) { _amostras.clear() }
        gnssMs = 0
        pastaLog.mkdirs()
        val hora = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(pastaLog, "slam_${nome.ifBlank { "levantamento" }}_$hora.csv")
        arquivoLog = f
        csv = FileWriter(f).apply {
            write("celular_ms;scanner_tempo;estado;n_pontos;x;y;z;pitch;roll;yaw;passo_m;vel_m_s;nivel;motivo;tremor_m;pontos_normais;gnss_e;gnss_n\n")
        }
        rodando = true
        situacao = Situacao(Nivel.OK, "aguardando o primeiro quadro", 0.0, 0.0, 0, 0.0, 0, 0.0)
    }

    fun parar() {
        rodando = false
        try { csv?.close() } catch (_: Exception) { }
        csv = null
        situacao = situacao.copy(nivel = Nivel.PARADO, motivo = "levantamento parado", repetir = false, perdidoPose = false)
    }

    /** Posição do receptor do scanner (GGA). Só RTK fixo entra na conferência GNSS × SLAM. */
    fun gnss(lat: Double?, lon: Double?, qualidade: Int, agoraMs: Long = System.currentTimeMillis()) {
        if (lat == null || lon == null || qualidade != 4) return
        if (lat0.isNaN()) { lat0 = lat; lon0 = lon }
        val k = 6_371_000.0 * Math.PI / 180.0
        gnssE = (lon - lon0) * k * cos(Math.toRadians(lat0)); gnssN = (lat - lat0) * k; gnssMs = agoraMs
    }

    /** Chamado a cada quadro de nuvem. */
    fun quadro(q: Protocolo.QuadroNuvem, agoraMs: Long = System.currentTimeMillis()): Situacao {
        if (!rodando) return situacao
        quadros++
        if (primeiroMs == 0L) primeiroMs = agoraMs
        val p = q.pose
        var nivel = Nivel.OK
        val motivos = ArrayList<String>()
        var passo = 0.0
        var vel = 0.0
        var repetir = false
        var perdidoPose = false
        var tremorMed = 0.0
        var tremor = false

        if (p.any { it.isNaN() || it.isInfinite() }) {
            nivel = Nivel.PERDIDO; perdidoPose = true; motivos.add("pose inválida")
        } else {
            val ant = ultimaPose
            if (ant != null) {
                val dx = (p[0] - ant[0]).toDouble(); val dy = (p[1] - ant[1]).toDouble(); val dz = (p[2] - ant[2]).toDouble()
                passo = sqrt(dx * dx + dy * dy + dz * dz)
                val dt = maxOf((agoraMs - ultimoMs) / 1000.0, 0.05)
                vel = passo / dt
                distancia += passo
                val med = mediana(velocidades)
                when {
                    vel > vPerdido -> { nivel = Nivel.PERDIDO; perdidoPose = true; motivos.add("salto de %.1f m em %.1f s".format(passo, dt)) }
                    abs(dz) > 3.0 -> { nivel = Nivel.PERDIDO; perdidoPose = true; motivos.add("pulo vertical de %.1f m".format(dz)) }
                    vel > vAtencao -> { nivel = Nivel.ATENCAO; repetir = true; motivos.add("rápido demais: %.1f m/s".format(vel)) }
                    med != null && med > 0.2 && vel > 3 * med && vel > 1.5 ->
                        { nivel = Nivel.ATENCAO; repetir = true; motivos.add("salto: %.1f m/s (normal %.1f)".format(vel, med)) }
                }
                velocidades.addLast(vel); if (velocidades.size > 15) velocidades.removeFirst()
            }
            ultimaPose = p.copyOf()

            // posição tremendo: segunda diferença |p(i) - 2 p(i-1) + p(i-2)|
            poses.addLast(doubleArrayOf(p[0].toDouble(), p[1].toDouble(), p[2].toDouble())); if (poses.size > 3) poses.removeFirst()
            if (poses.size == 3) {
                val (a, b, c) = Triple(poses[0], poses[1], poses[2])
                var s = 0.0
                for (k in 0..2) { val d = c[k] - 2 * b[k] + a[k]; s += d * d }
                tremores.addLast(sqrt(s)); if (tremores.size > 4) tremores.removeFirst()
            }
            tremorMed = if (tremores.size >= 4) mediana(tremores) ?: 0.0 else 0.0
            if (tremorMed > tremorAtencao) {
                tremor = true; repetir = true
                if (nivel == Nivel.OK) nivel = Nivel.ATENCAO
                motivos.add("posição tremendo (%.0f cm): pare, volte alguns metros e ande devagar".format(tremorMed * 100))
            }
        }

        // ambiente pobre: pontos da janela de 4 s contra o normal DESTE levantamento
        janelaPts.addLast(q.nPontos); if (janelaPts.size > 8) janelaPts.removeFirst()
        val medJanela = mediana(janelaPts.map { it.toDouble() }) ?: q.nPontos.toDouble()
        if (++quadrosNaJanela >= 8 && janelaPts.size == 8) {
            quadrosNaJanela = 0
            if (distancia > 2.0) historicoPts.add(medJanela)       // só depois de começar a andar (parado no início não conta)
        }
        val normal = pontosNormais
        var pobre = false
        if (normal != null && medJanela < pobreFracao * normal) {
            if (pobreDesdeMs == 0L) pobreDesdeMs = agoraMs
            if ((agoraMs - pobreDesdeMs) / 1000.0 >= pobreSegundos) {
                pobre = true
                if (nivel == Nivel.OK) nivel = Nivel.ATENCAO
                motivos.add("ambiente pobre: %d pontos, %.0f%% do normal (%d) há %.0f s".format(
                    medJanela.toInt(), 100 * medJanela / normal, normal.toInt(), (agoraMs - pobreDesdeMs) / 1000.0))
            }
        } else pobreDesdeMs = 0L
        if (pobre && !estavaPobre) repetir = true                // apita ao entrar no trecho pobre
        estavaPobre = pobre

        if (estadoNormal == null && quadros >= 3) estadoNormal = q.estado
        if (estadoNormal != null && q.estado != estadoNormal) {
            if (nivel == Nivel.OK) nivel = Nivel.ATENCAO
            motivos.add("código de estado %d (normal %d)".format(q.estado, estadoNormal))
        }

        val temGnss = gnssMs > 0 && agoraMs - gnssMs < 1500
        val e = if (temGnss) gnssE else Double.NaN
        val n = if (temGnss) gnssN else Double.NaN
        if (p.none { it.isNaN() || it.isInfinite() })
            synchronized(_amostras) {
                _amostras.add(Amostra(agoraMs, p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), q.nPontos, tremor, pobre,
                    nivel == Nivel.PERDIDO, nivel == Nivel.ATENCAO, e, n))
            }

        val qps = if (agoraMs > primeiroMs) (quadros - 1) * 1000.0 / (agoraMs - primeiroMs) else 0.0
        ultimoMs = agoraMs
        val motivo = if (motivos.isEmpty()) "SLAM normal" else motivos.joinToString("; ")
        situacao = Situacao(nivel, motivo, vel, passo, q.nPontos, qps, q.estado, distancia, repetir, perdidoPose)
        try {
            csv?.write("%d;%d;%d;%d;%.3f;%.3f;%.3f;%.2f;%.2f;%.2f;%.3f;%.3f;%s;%s;%.3f;%d;%.3f;%.3f\n".format(Locale.US,
                agoraMs, q.tempo, q.estado, q.nPontos, p[0], p[1], p[2], p[3], p[4], p[5], passo, vel, nivel.name,
                motivo.replace(';', ','), tremorMed, (normal ?: 0.0).toInt(), e, n))
            if (quadros % 20 == 0) csv?.flush()
        } catch (_: Exception) { }
        return situacao
    }

    /** Chamado periodicamente (1 s): detecta quadros que pararam de chegar. */
    fun verificarAtraso(agoraMs: Long = System.currentTimeMillis()): Situacao {
        if (!rodando || ultimoMs == 0L) return situacao
        val atraso = (agoraMs - ultimoMs) / 1000.0
        if (atraso > atrasoPerdidoS) {
            situacao = situacao.copy(nivel = Nivel.PERDIDO, motivo = "nenhum quadro há %.0f s".format(atraso), perdidoPose = false)
        } else if (atraso > atrasoAtencaoS && situacao.nivel == Nivel.OK) {
            situacao = situacao.copy(nivel = Nivel.ATENCAO, motivo = "quadros atrasados (%.1f s)".format(atraso), repetir = false)
        }
        return situacao
    }

    /**
     * Onde recomeçar depois de se perder: o último quadro "bom" (sem atenção nem perdido) pelo menos [antesS] s antes
     * do primeiro sinal de problema do trecho final. Devolve o índice em [amostras] e a distância andada dali até o
     * começo do problema (quanto voltar).
     */
    fun pontoRetomada(antesS: Double = 8.0): Pair<Int, Double>? = Companion.pontoRetomada(amostras, antesS)

    private fun mediana(v: Collection<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    companion object {
        fun pontoRetomada(a: List<Amostra>, antesS: Double = 8.0): Pair<Int, Double>? {
            if (a.size < 2) return null
            // início do problema: volta do fim enquanto os quadros estão em atenção/perdido/tremor
            var i = a.size - 1
            while (i > 0 && (a[i].perdido || a[i].tremor || a[i].atencao)) i--
            val limite = a[i].ms - (antesS * 1000).toLong()
            var j = i
            while (j > 0 && (a[j].ms > limite || a[j].perdido || a[j].tremor || a[j].pobre)) j--
            var dist = 0.0
            for (k in j + 1..i) {                                    // depois do problema a pose não vale: conta até ele
                val dx = a[k].x - a[k - 1].x; val dy = a[k].y - a[k - 1].y; val dz = a[k].z - a[k - 1].z
                dist += sqrt(dx * dx + dy * dy + dz * dz)
            }
            return j to dist
        }
    }
}
