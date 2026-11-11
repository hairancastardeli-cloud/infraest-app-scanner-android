package br.scanner.campo

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Rumo do scanner pelo celular preso nele (apontando para o mesmo lado). Usa o vetor de rotação do Android, que já junta
 * giroscópio + acelerômetro + bússola (o giroscópio segura o rumo quando a bússola oscila perto de metal).
 *
 * Eixo usado: a direção para onde o celular "aponta" na horizontal. Celular deitado → o topo (eixo Y); celular em pé
 * (tela para o operador) → as costas (câmera, −Z). Fica o eixo mais horizontal dos dois. O rumo sai do NORTE DA QUADRÍCULA
 * UTM: magnético + declinação (modelo do Android pela posição e data) − convergência meridiana.
 * Erros fixos (celular torto no suporte, metal do scanner) não importam: a calibração pela leitura dupla os absorve.
 */
class RumoCelular(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)

    val disponivel: Boolean get() = sensor != null

    /** Rumo magnético (graus, 0 = norte, horário) do eixo escolhido; NaN sem leitura. */
    @Volatile var magnetico = Double.NaN
        private set
    /** "topo" ou "costas": o eixo usado (trocar de eixo entre a calibração e a medida invalida a calibração). */
    @Volatile var eixo = ""
        private set
    /** Precisão estimada pelo Android (graus); NaN se o aparelho não informa. */
    @Volatile var precisaoGraus = Double.NaN
        private set
    /** Qualidade da bússola segundo o Android (SensorManager.SENSOR_STATUS_*). */
    @Volatile var qualidade = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set
    @Volatile var ultimoMs = 0L
        private set

    private var declinacao = 0.0
    private var convergencia = 0.0

    fun ligar() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun desligar() = sm.unregisterListener(this)

    /** Atualiza declinação e convergência pela posição (chamar com o GGA; muda devagar). */
    fun posicao(lat: Double, lon: Double, alt: Double) {
        declinacao = GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), System.currentTimeMillis()).declination.toDouble()
        convergencia = convergenciaMeridiana(lat, lon)
    }

    /** Rumo da quadrícula UTM (graus 0–360) agora; NaN sem leitura recente. */
    fun quadricula(agoraMs: Long = System.currentTimeMillis()): Double =
        if (magnetico.isNaN() || agoraMs - ultimoMs > 1500) Double.NaN else normalizar(magnetico + declinacao - convergencia)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // colunas da matriz = eixos do celular no mundo (X leste, Y norte, Z para cima)
        val yE = r[1].toDouble(); val yN = r[4].toDouble(); val yU = r[7].toDouble()
        val zE = -r[2].toDouble(); val zN = -r[5].toDouble(); val zU = -r[8].toDouble()
        val (e1, n1, nome) = if (abs(yU) <= abs(zU)) Triple(yE, yN, "topo") else Triple(zE, zN, "costas")
        if (sqrt(e1 * e1 + n1 * n1) < 0.3) return                           // eixo quase vertical: rumo indefinido
        magnetico = normalizar(Math.toDegrees(atan2(e1, n1)))
        eixo = nome
        if (e.values.size >= 5 && e.values[4] >= 0) precisaoGraus = Math.toDegrees(e.values[4].toDouble())
        ultimoMs = System.currentTimeMillis()
    }

    override fun onAccuracyChanged(s: Sensor, precisao: Int) { qualidade = precisao }

    companion object {
        fun normalizar(g: Double): Double { val x = g % 360.0; return if (x < 0) x + 360.0 else x }

        /** Diferença a − b em (−180, 180]. */
        fun diferenca(a: Double, b: Double): Double { var d = normalizar(a - b); if (d > 180) d -= 360; return d }

        /** Média circular (graus) e desvio circular aproximado (graus). */
        fun media(v: List<Double>): Pair<Double, Double> {
            if (v.isEmpty()) return Double.NaN to Double.NaN
            var s = 0.0; var c = 0.0
            for (g in v) { s += Math.sin(Math.toRadians(g)); c += Math.cos(Math.toRadians(g)) }
            val m = normalizar(Math.toDegrees(atan2(s, c)))
            val rr = sqrt(s * s + c * c) / v.size
            val dp = if (rr >= 1.0) 0.0 else Math.toDegrees(sqrt(-2 * Math.log(rr)))
            return m to dp
        }

        /** Convergência meridiana UTM (graus): norte da quadrícula = norte verdadeiro − γ. */
        fun convergenciaMeridiana(lat: Double, lon: Double): Double {
            val mc = 6.0 * Geodesia.fuso(lon) - 183
            return Math.toDegrees(Math.atan(Math.tan(Math.toRadians(lon - mc)) * Math.sin(Math.toRadians(lat))))
        }
    }
}
