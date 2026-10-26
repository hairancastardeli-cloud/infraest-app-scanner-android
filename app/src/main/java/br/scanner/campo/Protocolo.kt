package br.scanner.campo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocolo do scanner GeoSun (lido do app FireEye 3.2.0):
 *   "HOLD" (4 bytes) + comando (4 bytes) + tamanho dos dados (int32 big-endian) + dados.
 * Os números de controle (tamanho, contadores) vêm em big-endian; os floats da pose vêm em little-endian.
 */
object Protocolo {
    val CABECALHO = byteArrayOf(0x48, 0x4F, 0x4C, 0x44)          // "HOLD"

    // comandos enviados ao scanner
    val INICIAR = cmd(0x10, 0x00, 0x00, 0x01)
    val PARAR = cmd(0x10, 0x00, 0x00, 0x02)
    val ROTACAO_MOTOR = cmd(0x10, 0x00, 0x00, 0x03)
    val PONTO_CONTROLE = cmd(0x10, 0x00, 0x00, 0x06)
    val NOME_PROJETO = cmd(0x10, 0x00, 0x00, 0x08)
    val PARAMETROS_AMOSTRA = cmd(0x10, 0x00, 0x00, 0x09)
    val RTCM = cmd(0x12, 0x00, 0x00, 0x01)
    val VERSAO_FIRMWARE = cmd(0x11, 0x00, 0x14, 0x00)

    // mensagens recebidas do scanner
    enum class Tipo(val codigo: ByteArray) {
        NUVEM(cmd(0x11, 0x00, 0x00, 0x40)),
        BATERIA(cmd(0x11, 0x00, 0x00, 0x20)),
        ARMAZENAMENTO(cmd(0x10, 0x00, 0x01, 0x07)),
        ESTADO_SOLUCAO(cmd(0x11, 0x00, 0x00, 0x31)),
        CHECAGEM_SISTEMA(cmd(0x11, 0x00, 0x00, 0x30)),
        PARAMETROS(cmd(0x11, 0x00, 0x00, 0x08)),
        GNSS(cmd(0x11, 0x00, 0x00, 0x04)),
        ESTACAO_BASE(cmd(0x11, 0x00, 0x00, 0x05)),
        CALIBRACAO_CSV(cmd(0x11, 0x00, 0x00, 0x07)),    // calibração do lidar (HesaiCalibration.csv), enviada ao conectar      // no FireEye (BASE_STATION) é a linha NMEA GGA do receptor do scanner
        IMU(cmd(0x11, 0x00, 0x00, 0x02)),
        LIDAR(cmd(0x11, 0x00, 0x00, 0x01)),
        MOTOR(cmd(0x11, 0x00, 0x00, 0x03)),
        RESP_INICIAR(cmd(0x10, 0x00, 0x01, 0x01)),
        RESP_PARAR(cmd(0x10, 0x00, 0x01, 0x02)),
        RESP_PONTO_CONTROLE(cmd(0x10, 0x00, 0x01, 0x06)),
        RESP_NOME_PROJETO(cmd(0x10, 0x00, 0x01, 0x08)),
        FIRMWARE(cmd(0x11, 0x00, 0x14, 0x01)),
        DESCONHECIDO(ByteArray(0));

        companion object {
            fun de(c: ByteArray): Tipo = entries.firstOrNull { it.codigo.contentEquals(c) } ?: DESCONHECIDO
        }
    }

    class Mensagem(val tipo: Tipo, val codigo: ByteArray, val dados: ByteArray)

    private fun cmd(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    fun montar(comando: ByteArray, dados: ByteArray = ByteArray(0)): ByteArray {
        val bb = ByteBuffer.allocate(12 + dados.size).order(ByteOrder.BIG_ENDIAN)
        bb.put(CABECALHO).put(comando).putInt(dados.size).put(dados)
        return bb.array()
    }

    /** INICIAR: modo (1 = levantamento) + nome "<pasta>_<projeto>" em bytes + hora do celular em ms (int64 BE). */
    fun dadosIniciar(pasta: String, projeto: String, agoraMs: Long = System.currentTimeMillis()): ByteArray {
        val nome = "${pasta}_$projeto".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + nome.size + 8).order(ByteOrder.BIG_ENDIAN)
            .put(1.toByte()).put(nome).putLong(agoraMs).array()
    }

    /**
     * Parâmetros de amostragem enviados 0,5 s depois do INICIAR (padrões do GS-102G no FireEye):
     * taxa 0,5 (float), intervalo 10 (int), distância de exibição 0,2 m e de cálculo 0,5 m (double),
     * modo debug 0 (byte), intervalo de quadro-chave 1 (int). Tudo big-endian.
     */
    fun dadosAmostragem(
        taxa: Float = 0.5f, intervalo: Int = 10, distExibicao: Double = 0.2,
        distCalculo: Double = 0.5, debug: Boolean = false, quadroChave: Int = 1,
    ): ByteArray = ByteBuffer.allocate(4 + 4 + 8 + 8 + 1 + 4).order(ByteOrder.BIG_ENDIAN)
        .putFloat(taxa).putInt(intervalo).putDouble(distExibicao).putDouble(distCalculo)
        .put(if (debug) 1.toByte() else 0.toByte()).putInt(quadroChave).array()

    /**
     * Remonta as mensagens a partir do fluxo TCP (pedaços de tamanho qualquer). Procura "HOLD", lê o tamanho e
     * espera a mensagem inteira; lixo antes de um "HOLD" é descartado. (O remontador do FireEye perde o resto
     * do pacote quando dois chegam colados.)
     */
    class Remontador(private val limite: Int = 16 * 1024 * 1024) {
        private val buf = ByteArrayOutputStream()
        var descartados = 0L
            private set

        fun alimentar(pedaco: ByteArray, n: Int = pedaco.size): List<Mensagem> {
            buf.write(pedaco, 0, n)
            val b = buf.toByteArray()
            val saida = ArrayList<Mensagem>()
            var i = 0
            while (true) {
                val h = acharCabecalho(b, i)
                if (h < 0) {                                   // sem "HOLD": guarda só os 3 últimos bytes (cabeçalho partido)
                    val resto = maxOf(i, b.size - 3)
                    descartados += (resto - i).coerceAtLeast(0)
                    i = resto
                    break
                }
                descartados += (h - i)
                if (b.size - h < 12) { i = h; break }
                val tam = ByteBuffer.wrap(b, h + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                if (tam < 0 || tam > limite) {                  // tamanho absurdo: era um "HOLD" no meio dos dados
                    i = h + 4; continue
                }
                if (b.size - h < 12 + tam) { i = h; break }
                val codigo = b.copyOfRange(h + 4, h + 8)
                saida.add(Mensagem(Tipo.de(codigo), codigo, b.copyOfRange(h + 12, h + 12 + tam)))
                i = h + 12 + tam
            }
            buf.reset()
            if (i < b.size) buf.write(b, i, b.size - i)
            return saida
        }

        private fun acharCabecalho(b: ByteArray, de: Int): Int {
            var k = de
            while (k + 4 <= b.size) {
                if (b[k] == CABECALHO[0] && b[k + 1] == CABECALHO[1] && b[k + 2] == CABECALHO[2] && b[k + 3] == CABECALHO[3]) return k
                k++
            }
            return -1
        }
    }

    /** Bateria: tensão em mV nos bytes 5-6 (inteiro de 16 bits). Tabela de tensão → % do FireEye (equipamentos 102G/130G). */
    private val TABELA_BATERIA = listOf(16000 to 100, 15880 to 95, 15720 to 90, 15600 to 85, 15358 to 80, 15198 to 75, 14727 to 70,
        14527 to 65, 14476 to 60, 14352 to 55, 14251 to 50, 14149 to 45, 14059 to 40, 13946 to 35, 13852 to 30, 13772 to 25,
        13648 to 20, 13528 to 15, 13100 to 10)

    fun lerBateria(d: ByteArray): Pair<Int, Int>? {                    // (milivolts, porcentagem)
        if (d.size < 7) return null
        val mv = ((d[5].toInt() and 0xff) shl 8) or (d[6].toInt() and 0xff)
        return mv to (TABELA_BATERIA.firstOrNull { mv >= it.first }?.second ?: 5)
    }

    val ITENS_CHECAGEM = listOf("Armazenamento", "Lidar", "Sistema principal", "Bateria", "Câmera principal", "Câmera visual")

    /** Checagem do sistema do scanner (11 00 00 30): um byte por item, 1 = OK, outro valor = pendente/com problema. */
    fun lerChecagem(d: ByteArray): IntArray = IntArray(ITENS_CHECAGEM.size) { i -> if (i >= d.size) -1 else if (d[i].toInt() == 1) 1 else 0 }

    /** Memória: byte 0 = GB usados, byte 1 = GB totais. */
    fun lerMemoria(d: ByteArray): Pair<Int, Int>? = if (d.size >= 2) (d[0].toInt() and 0xff) to (d[1].toInt() and 0xff) else null

    /** Estado da solução GNSS (inteiro big-endian). No FireEye: até 3 = "RTK fixo", acima = flutuante. */
    fun lerEstadoSolucao(d: ByteArray): Int? = if (d.size >= 4) ByteBuffer.wrap(d, 0, 4).order(ByteOrder.BIG_ENDIAN).int else null

    /** Quadro de nuvem: cabeçalho de 48 bytes (ver PointCloudDataParseDelegate do FireEye). */
    class QuadroNuvem(
        val nPontos: Int, val bytesPorPonto: Int, val tempo: Long, val estado: Int,
        val pose: FloatArray,                   // x, y, z, pitch, roll, yaw
        val pontos: FloatArray,                 // x, y, z intercalados
    )

    fun lerNuvem(d: ByteArray, maxPontos: Int = 4000): QuadroNuvem? {
        if (d.size < 48) return null
        val be = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN)
        val n = be.getInt(0); val passo = be.getInt(4)
        if (n <= 0 || passo < 12) return null
        val extra = be.getInt(44)
        if (extra < 0 || d.size - 48 < n.toLong() * passo + extra) return null
        val tempo = be.getLong(8); val estado = be.getInt(16)
        val le = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val pose = FloatArray(6) { le.getFloat(20 + 4 * it) }
        val salto = maxOf(1, n / maxPontos)                // para desenhar basta uma amostra
        val m = (n + salto - 1) / salto
        val pts = FloatArray(m * 3)
        var j = 0
        var k = 0
        while (k < n && j < m) {
            val o = 48 + extra + k * passo
            pts[3 * j] = le.getFloat(o); pts[3 * j + 1] = le.getFloat(o + 4); pts[3 * j + 2] = le.getFloat(o + 8)
            j++; k += salto
        }
        return QuadroNuvem(n, passo, tempo, estado, pose, pts)
    }
}
