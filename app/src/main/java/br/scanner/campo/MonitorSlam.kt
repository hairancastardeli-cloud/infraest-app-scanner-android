package br.scanner.campo

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Vigia do SLAM a partir dos quadros de nuvem que o scanner manda (pose por quadro). O FireEye recebe a mesma pose
 * e não avisa nada; aqui saem dois níveis de alarme:
 *
 *  ATENÇÃO (prestes a se perder):
 *   - velocidade acima de [vAtencao] m/s (andando normal: até ~1,5 m/s);
 *   - salto: velocidade 3× maior que a mediana dos últimos quadros;
 *   - poucos pontos no quadro (< 35% da mediana): lugar apertado/sem feições — foi o que derrubou o SLAM no BAC;
 *   - quadros atrasados (> [atrasoAtencaoS] s sem quadro novo);
 *   - o código de estado do quadro mudou do valor normal (o significado ainda é desconhecido; fica registrado).
 *  PERDIDO:
 *   - deslocamento acima de [vPerdido] m/s (o PCC aborta quando o SLAM passa de 5 m por quadro de 1 s);
 *   - pose inválida (NaN/infinito) ou pulo vertical > 3 m num quadro;
 *   - nenhum quadro há mais de [atrasoPerdidoS] s com o levantamento rodando.
 *
 * Todo quadro vai para um CSV (para calibrar os limites com levantamentos reais e descobrir o código de estado).
 */
class MonitorSlam(private val pastaLog: File) {
    enum class Nivel { PARADO, OK, ATENCAO, PERDIDO }

    data class Situacao(
        val nivel: Nivel, val motivo: String, val velocidade: Double, val passo: Double,
        val pontos: Int, val quadrosPorS: Double, val estado: Int, val distanciaTotal: Double,
    )

    var vAtencao = 2.5
    var vPerdido = 5.0
    var atrasoAtencaoS = 2.5
    var atrasoPerdidoS = 6.0

    private var ultimaPose: FloatArray? = null
    private var ultimoMs = 0L
    private var primeiroMs = 0L
    private val velocidades = ArrayDeque<Double>()
    private val contagens = ArrayDeque<Int>()
    private var estadoNormal: Int? = null
    private var quadros = 0
    private var distancia = 0.0
    private var csv: FileWriter? = null
    val totalQuadros: Int get() = quadros
    var arquivoLog: File? = null
        private set
    @Volatile var rodando = false
        private set
    @Volatile var situacao = Situacao(Nivel.PARADO, "", 0.0, 0.0, 0, 0.0, 0, 0.0)
        private set

    fun comecar(nome: String) {
        parar()
        ultimaPose = null; ultimoMs = 0; primeiroMs = 0; quadros = 0; distancia = 0.0
        velocidades.clear(); contagens.clear(); estadoNormal = null
        pastaLog.mkdirs()
        val hora = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(pastaLog, "slam_${nome.ifBlank { "levantamento" }}_$hora.csv")
        arquivoLog = f
        csv = FileWriter(f).apply {
            write("celular_ms;scanner_tempo;estado;n_pontos;x;y;z;pitch;roll;yaw;passo_m;vel_m_s;nivel;motivo\n")
        }
        rodando = true
        situacao = Situacao(Nivel.OK, "aguardando o primeiro quadro", 0.0, 0.0, 0, 0.0, 0, 0.0)
    }

    fun parar() {
        rodando = false
        try { csv?.close() } catch (_: Exception) { }
        csv = null
        situacao = situacao.copy(nivel = Nivel.PARADO, motivo = "levantamento parado")
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

        if (p.any { it.isNaN() || it.isInfinite() }) {
            nivel = Nivel.PERDIDO; motivos.add("pose inválida")
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
                    vel > vPerdido -> { nivel = Nivel.PERDIDO; motivos.add("salto de %.1f m em %.1f s".format(passo, dt)) }
                    abs(dz) > 3.0 -> { nivel = Nivel.PERDIDO; motivos.add("pulo vertical de %.1f m".format(dz)) }
                    vel > vAtencao -> { nivel = Nivel.ATENCAO; motivos.add("rápido demais: %.1f m/s".format(vel)) }
                    med != null && med > 0.2 && vel > 3 * med && vel > 1.5 ->
                        { nivel = Nivel.ATENCAO; motivos.add("salto: %.1f m/s (normal %.1f)".format(vel, med)) }
                }
                velocidades.addLast(vel); if (velocidades.size > 15) velocidades.removeFirst()
            }
            ultimaPose = p.copyOf()
        }

        val medPts = mediana(contagens.map { it.toDouble() })
        if (medPts != null && contagens.size >= 5 && q.nPontos < 0.35 * medPts) {
            if (nivel == Nivel.OK) nivel = Nivel.ATENCAO
            motivos.add("poucos pontos (%d, normal %d): lugar apertado ou sem feições".format(q.nPontos, medPts.toInt()))
        }
        contagens.addLast(q.nPontos); if (contagens.size > 20) contagens.removeFirst()

        if (estadoNormal == null && quadros >= 3) estadoNormal = q.estado
        if (estadoNormal != null && q.estado != estadoNormal) {
            if (nivel == Nivel.OK) nivel = Nivel.ATENCAO
            motivos.add("código de estado %d (normal %d)".format(q.estado, estadoNormal))
        }

        val qps = if (agoraMs > primeiroMs) (quadros - 1) * 1000.0 / (agoraMs - primeiroMs) else 0.0
        ultimoMs = agoraMs
        val motivo = if (motivos.isEmpty()) "SLAM normal" else motivos.joinToString("; ")
        situacao = Situacao(nivel, motivo, vel, passo, q.nPontos, qps, q.estado, distancia)
        try {
            csv?.write("%d;%d;%d;%d;%.3f;%.3f;%.3f;%.2f;%.2f;%.2f;%.3f;%.3f;%s;%s\n".format(Locale.US,
                agoraMs, q.tempo, q.estado, q.nPontos, p[0], p[1], p[2], p[3], p[4], p[5], passo, vel, nivel.name, motivo.replace(';', ',')))
            if (quadros % 20 == 0) csv?.flush()
        } catch (_: Exception) { }
        return situacao
    }

    /** Chamado periodicamente (1 s): detecta quadros que pararam de chegar. */
    fun verificarAtraso(agoraMs: Long = System.currentTimeMillis()): Situacao {
        if (!rodando || ultimoMs == 0L) return situacao
        val atraso = (agoraMs - ultimoMs) / 1000.0
        if (atraso > atrasoPerdidoS) {
            situacao = situacao.copy(nivel = Nivel.PERDIDO, motivo = "nenhum quadro há %.0f s".format(atraso))
        } else if (atraso > atrasoAtencaoS && situacao.nivel == Nivel.OK) {
            situacao = situacao.copy(nivel = Nivel.ATENCAO, motivo = "quadros atrasados (%.1f s)".format(atraso))
        }
        return situacao
    }

    private fun mediana(v: Collection<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
