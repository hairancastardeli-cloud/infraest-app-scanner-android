package br.scanner.campo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.scanner.campo.Estilo.dp
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }

    private lateinit var equip: Equipamento
    private lateinit var conector: ConectorScanner
    private lateinit var btConectarScanner: Button
    private lateinit var tvScanner: TextView
    private var buscandoScanner = false

    // preparo do scanner (o FireEye só deixa iniciar depois disso): parâmetros, calibração do lidar e checagem do sistema
    private lateinit var tvPreparo: TextView
    private var paramsOk = false
    private var csvOk = false
    private var firmware = ""
    private var checagem = IntArray(Protocolo.ITENS_CHECAGEM.size) { -1 }
    private var checagemRecebida = false
    private var inicioEnviadoMs = 0L
    private var respostaInicioMs = 0L
    private lateinit var ntrip: Ntrip
    private lateinit var monitor: MonitorSlam
    private lateinit var vista: VistaTopo

    // topo (sempre visível): estado do SLAM e etiquetas
    private lateinit var faixaSlam: LinearLayout
    private lateinit var tvSlamTitulo: TextView
    private lateinit var tvSlamMotivo: TextView
    private lateinit var tileScanner: Tile
    private lateinit var tileBateria: Tile
    private lateinit var tileMemoria: Tile
    private lateinit var tileGnss: Tile
    private lateinit var tileRtk: Tile
    private lateinit var tilePrecisao: Tile
    private lateinit var gradeTiles: LinearLayout
    private lateinit var barraAcao: LinearLayout
    private lateinit var tvTempo: TextView
    private lateinit var btPonto: Button
    private var memoriaTotal = -1
    private var inicioLevantamentoMs = 0L
    private var ultimoGst: Gst? = null
    private var ultimoGstMs = 0L
    private val diag = Diagnostico()

    private class Tile(val bloco: LinearLayout, val valor: TextView, val sub: TextView)

    // abas
    private lateinit var abaLevantamento: ScrollView
    private lateinit var abaRtk: ScrollView
    private lateinit var btAbaLev: Button
    private lateinit var btAbaRtk: Button
    private lateinit var barraAbas: LinearLayout

    // levantamento
    private lateinit var etProjeto: Estilo.Campo
    private lateinit var btIniciar: Button
    private lateinit var numVel: Estilo.Numero
    private lateinit var numPontos: Estilo.Numero
    private lateinit var numQuadros: Estilo.Numero
    private lateinit var numAndado: Estilo.Numero
    private var levantando = false
    private var silenciadoAte = 0L
    private var ultimoAlarme = 0L

    // RTK
    private lateinit var etHost: Estilo.Campo
    private lateinit var etPorta: Estilo.Campo
    private lateinit var etUsuario: Estilo.Campo
    private lateinit var etSenha: Estilo.Campo
    private lateinit var etMount: Estilo.Campo
    private lateinit var btObter: Button
    private lateinit var btConectarRtk: Button
    private lateinit var tvRtk: TextView
    private var mountpoints: List<Ntrip.Mountpoint> = emptyList()
    private var senhaVisivel = false

    private var tecladoVisivel = false
    private val tom by lazy { ToneGenerator(AudioManager.STREAM_ALARM, 100) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Estilo.FUNDO
        window.navigationBarColor = Estilo.FUNDO
        monitor = MonitorSlam(getExternalFilesDir("logs_slam") ?: filesDir)
        montarTela()
        conector = ConectorScanner(this)
        equip = Equipamento(::aoMensagem) { ok, txt ->
            ui.post {
                if (ok) pintaScanner("Conectado", Estilo.OK, subPreparo())
                else if (equip.ativo) pintaScanner("Reconectando…", Estilo.ATENCAO, txt.take(36))
                else pintaScanner("Desconectado", Estilo.NEUTRO, "")
                statusScanner(txt, if (ok) Estilo.OK else Estilo.TEXTO_2)
                btConectarScanner.text = if (ok || equip.ativo) "Desconectar do scanner" else "Conectar ao scanner"
                Estilo.aplicarTipo(this, btConectarScanner, if (ok || equip.ativo) Estilo.TipoBotao.PERIGO else Estilo.TipoBotao.CHEIO)
            }
        }
        ntrip = Ntrip(this, { rtcm -> equip.enviar(Protocolo.RTCM, rtcm) }) { est, txt -> ui.post { mostrarRtk(est, txt) } }
        ui.postDelayed(object : Runnable {
            override fun run() {
                diag.gravar(getExternalFilesDir("logs_slam") ?: filesDir)
                if (levantando) mostrarSlam(monitor.verificarAtraso())
                atualizarRtkContadores(); atualizarTempo()
                ui.postDelayed(this, 1000)
            }
        }, 1000)
    }

    override fun onDestroy() {
        ntrip.desconectar(); equip.parar(); conector.liberar(); monitor.parar()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ConectorScanner.REQ_PERMISSOES) return
        if (conector.permissoesConcedidas(grantResults)) conectarScanner()
        else statusScanner("Sem a permissão de localização o Android não deixa o app listar as redes Wi-Fi. Conceda e toque em Conectar de novo.", Estilo.ERRO)
    }

    // ------------------------------------------------------------------ conexão com o scanner (como no FireEye)
    private fun statusScanner(t: String, cor: Int) { tvScanner.text = t; tvScanner.setTextColor(cor) }

    private fun botaoConectarScanner() {
        if (buscandoScanner || equip.ativo) { desconectarScanner(); return }
        conectarScanner()
    }

    private fun desconectarScanner() {
        buscandoScanner = false
        conector.liberar(); equip.parar()
        btConectarScanner.text = "Conectar ao scanner"; Estilo.aplicarTipo(this, btConectarScanner, Estilo.TipoBotao.CHEIO)
        statusScanner("Desconectado.", Estilo.TEXTO_2)
        pintaScanner("Desconectado", Estilo.NEUTRO, "")
    }

    private fun conectarScanner() {
        if (levantando) return
        if (!conector.garantirPermissoes()) { statusScanner("Aguardando a permissão de localização…", Estilo.TEXTO_2); return }
        if (!conector.wifiLigado()) {
            statusScanner("O Wi-Fi do celular está desligado. Ligue-o e toque em Conectar de novo.", Estilo.ATENCAO)
            conector.ligarWifi(); return
        }
        buscandoScanner = true
        btConectarScanner.text = "Cancelar"; Estilo.aplicarTipo(this, btConectarScanner, Estilo.TipoBotao.PERIGO)
        pintaScanner("Procurando…", Estilo.ATENCAO, "rede ${ConectorScanner.PREFIXO}…")
        statusScanner("Procurando o scanner…", Estilo.TEXTO_2)
        trabalho.execute {
            val ja = conector.procurarNasRedesAtuais()                  // já está na rede do scanner (conectou pelas configurações)?
            ui.post {
                if (!buscandoScanner) return@post
                if (ja != null) { aoAcharRede(ja, "rede atual"); return@post }
                if (!conector.localizacaoLigada()) {
                    buscandoScanner = false
                    btConectarScanner.text = "Conectar ao scanner"; Estilo.aplicarTipo(this, btConectarScanner, Estilo.TipoBotao.CHEIO)
                    statusScanner("Ligue a Localização do celular: o Android só deixa o app listar as redes Wi-Fi com ela ligada.", Estilo.ATENCAO)
                    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Ligar a Localização")
                        .setMessage("O Android só mostra a lista de redes Wi-Fi para apps com a Localização do celular ligada.")
                        .setPositiveButton("Abrir ajustes") { _, _ -> conector.abrirAjusteLocalizacao() }
                        .setNegativeButton("Agora não", null).show()
                    return@post
                }
                statusScanner("Procurando a rede Wi-Fi do scanner (nome começa com ${ConectorScanner.PREFIXO})…", Estilo.TEXTO_2)
                conector.escanear { redes -> ui.post { aoEscanear(redes) } }
            }
        }
    }

    private fun aoEscanear(redes: List<String>) {
        if (!buscandoScanner) return
        when (redes.size) {
            0 -> {
                buscandoScanner = false
                btConectarScanner.text = "Buscar de novo"; Estilo.aplicarTipo(this, btConectarScanner, Estilo.TipoBotao.CHEIO)
                pintaScanner("Não encontrado", Estilo.ERRO, "ligado e perto?")
                statusScanner("Não achei nenhuma rede ${ConectorScanner.PREFIXO}… O scanner está ligado e perto do celular? Aguarde alguns segundos após ligar e toque em Buscar de novo.", Estilo.ERRO)
            }
            1 -> conectarNaRede(redes[0])
            else -> {
                statusScanner("Achei ${redes.size} redes de scanner. Escolha uma.", Estilo.TEXTO_2)
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Escolha o scanner")
                    .setItems(redes.toTypedArray()) { _, i -> conectarNaRede(redes[i]) }
                    .setNegativeButton("Cancelar") { _, _ -> desconectarScanner() }
                    .setOnCancelListener { desconectarScanner() }.show()
            }
        }
    }

    private fun conectarNaRede(ssid: String) {
        statusScanner("Achei $ssid. Conectando… se o Android abrir uma janela, toque em Conectar.", Estilo.TEXTO_2)
        conector.conectar(ssid, { rede -> aoAcharRede(rede, ssid) },
            { motivo -> falhaScanner(motivo) },
            { if (buscandoScanner || equip.ativo) { equip.parar(); falhaScanner("a conexão Wi-Fi com $ssid caiu") } })
    }

    private fun aoAcharRede(rede: android.net.Network, nome: String) {
        buscandoScanner = false
        paramsOk = false; csvOk = false; firmware = ""; checagem = IntArray(Protocolo.ITENS_CHECAGEM.size) { -1 }; checagemRecebida = false
        atualizarPreparo()
        prefs.edit().putString("ultimo_ssid", nome).apply()
        statusScanner("Wi-Fi do scanner conectado ($nome). Abrindo a conexão…", Estilo.TEXTO_2)
        equip.iniciar(rede)
    }

    private fun falhaScanner(motivo: String) {
        buscandoScanner = false
        btConectarScanner.text = "Conectar ao scanner"; Estilo.aplicarTipo(this, btConectarScanner, Estilo.TipoBotao.CHEIO)
        pintaScanner("Desconectado", Estilo.ERRO, "falhou")
        statusScanner("Não conectou: $motivo", Estilo.ERRO)
    }

    override fun onBackPressed() {
        if (abaRtk.visibility == View.VISIBLE) mostrarAba(false) else super.onBackPressed()
    }

    // ------------------------------------------------------------------ mensagens do scanner
    private fun aoGga(texto: String) {
        ntrip.atualizarGga(texto.substring(texto.indexOf('$').coerceAtLeast(0)).lineSequence().first().trim { it <= ' ' } + "")
        val g = Gga.ler(texto)
        diag.gga(texto, g != null)
        if (g != null) ui.post {
            mostrarGnss(g, texto)
        }
    }

    private fun aoMensagem(m: Protocolo.Mensagem) {
        diag.mensagem(m)
        when (m.tipo) {
            Protocolo.Tipo.PARAMETROS -> { paramsOk = true; equip.enviar(Protocolo.VERSAO_FIRMWARE); ui.post { atualizarPreparo() } }
            Protocolo.Tipo.CALIBRACAO_CSV -> { csvOk = true; ui.post { atualizarPreparo() } }
            Protocolo.Tipo.FIRMWARE -> { firmware = String(m.dados, Charsets.ISO_8859_1).trim().take(40); ui.post { atualizarPreparo() } }
            Protocolo.Tipo.CHECAGEM_SISTEMA -> { checagem = Protocolo.lerChecagem(m.dados); checagemRecebida = true; ui.post { atualizarPreparo() } }
            Protocolo.Tipo.ESTACAO_BASE -> aoGga(String(m.dados, Charsets.ISO_8859_1))
            Protocolo.Tipo.BATERIA -> Protocolo.lerBateria(m.dados)?.let { (mv, pc) -> ui.post {
                pinta(tileBateria, "$pc%", if (pc <= 15) Estilo.ERRO else if (pc <= 30) Estilo.ATENCAO else Estilo.OK, "%.1f V".format(mv / 1000.0).replace('.', ','))
                diag.nota("bateria %d mV = %d%%".format(mv, pc)) } }
            Protocolo.Tipo.ARMAZENAMENTO -> Protocolo.lerMemoria(m.dados)?.let { (usado, total) -> ui.post {
                memoriaTotal = total
                val livre = total - usado
                if (total == 0) pinta(tileMemoria, "Sem cartão", Estilo.ERRO, "coloque o cartão")
                else pinta(tileMemoria, "$livre GB livres", if (livre <= 2) Estilo.ERRO else if (livre <= 8) Estilo.ATENCAO else Estilo.OK, "$usado de $total GB usados")
                atualizarPreparo() } }
            Protocolo.Tipo.ESTADO_SOLUCAO -> Unit
            Protocolo.Tipo.NUVEM -> {
                val q = Protocolo.lerNuvem(m.dados) ?: return
                val s = monitor.quadro(q)
                vista.adicionar(q)
                ui.post { mostrarSlam(s) }
            }
            Protocolo.Tipo.GNSS -> {                                      // se vier texto NMEA por aqui, também serve
                val t = String(m.dados, Charsets.ISO_8859_1)
                if (t.contains("GGA,")) aoGga(t)
            }
            Protocolo.Tipo.RESP_INICIAR -> { respostaInicioMs = System.currentTimeMillis(); diag.nota("início confirmado pelo scanner (resposta: ${m.dados.joinToString(" ") { "%02X".format(it) }})")
                ui.post { toast("Scanner confirmou o início") } }
            Protocolo.Tipo.RESP_PARAR -> ui.post { toast("Scanner confirmou a parada") }
            Protocolo.Tipo.RESP_PONTO_CONTROLE -> ui.post {
                val ok = m.dados.isNotEmpty() && m.dados[0].toInt() == 1
                toast(if (ok) "Ponto de controle gravado" else "O scanner recusou o ponto de controle")
                apito(if (ok) 1 else 3)
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ levantamento
    private fun alternarLevantamento() {
        if (!equip.conectado) { toast("Sem conexão com o scanner"); return }
        if (!levantando) {
            val falta = pendenciasDoScanner()
            if (falta.isNotEmpty()) {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("O scanner ainda não está pronto")
                    .setMessage("O FireEye espera isto antes de iniciar:\n\n" + falta.joinToString("\n") { "• $it" } +
                        "\n\nRecém-ligado, o scanner leva alguns minutos (o lidar precisa girar e a checagem terminar). Se iniciar agora, ele pode ignorar o comando.")
                    .setPositiveButton("Aguardar", null)
                    .setNegativeButton("Iniciar mesmo assim") { _, _ -> iniciarLevantamento() }.show()
                return
            }
            iniciarLevantamento()
        } else {
            pararLevantamento()
        }
    }

    private fun iniciarLevantamento() {
        run {
            val nome = etProjeto.edit.text.toString().trim().ifEmpty { "levantamento" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
            prefs.edit().putString("projeto", nome).apply()
            respostaInicioMs = 0L; inicioEnviadoMs = System.currentTimeMillis(); inicioLevantamentoMs = inicioEnviadoMs
            diag.nota("INICIAR enviado (projeto $nome); preparo: parâmetros=$paramsOk calibração=$csvOk checagem=${checagem.toList()}")
            equip.enviar(Protocolo.INICIAR, Protocolo.dadosIniciar("Campo", nome))
            ui.postDelayed({ equip.enviar(Protocolo.PARAMETROS_AMOSTRA, Protocolo.dadosAmostragem()) }, 500)
            monitor.comecar(nome); vista.limpar()
            levantando = true
            btIniciar.text = "Parar levantamento"; Estilo.aplicarTipo(this, btIniciar, Estilo.TipoBotao.PERIGO)
            esconderTeclado()
            mostrarSlam(monitor.situacao)
        }
    }

    private fun pararLevantamento() {
        run {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Parar o levantamento?")
                .setPositiveButton("Parar") { _, _ ->
                    equip.enviar(Protocolo.PARAR)
                    monitor.parar(); levantando = false; atualizarPreparo()
                    btIniciar.text = "Iniciar levantamento"; Estilo.aplicarTipo(this, btIniciar, Estilo.TipoBotao.SUCESSO)
                    mostrarSlam(monitor.situacao)
                    toast("Registro do SLAM: ${monitor.arquivoLog?.name ?: "-"}")
                }.setNegativeButton("Continuar", null).show()
        }
    }

    // ------------------------------------------------------------------ tiles do topo
    private fun criarTile(rotulo: String): Tile {
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        b.addView(Estilo.texto(this, rotulo.uppercase(), 10f, Estilo.TEXTO_3, true).apply { letterSpacing = 0.06f; isSingleLine = true })
        val v = Estilo.texto(this, "—", 15f, Estilo.TEXTO_2, true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        val s = Estilo.texto(this, " ", 11f, Estilo.TEXTO_2).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        b.addView(v); b.addView(s)
        val t = Tile(b, v, s); pinta(t, "—", Estilo.NEUTRO, " ")
        return t
    }

    private fun pinta(t: Tile, valor: String, cor: Int, sub: String? = null) {
        t.valor.text = valor
        t.bloco.background = Estilo.forma(this, Estilo.comAlfa(cor, if (cor == Estilo.NEUTRO) 14 else 34), 12, 1, Estilo.comAlfa(cor, if (cor == Estilo.NEUTRO) 70 else 160))
        t.valor.setTextColor(if (cor == Estilo.NEUTRO) Estilo.TEXTO_2 else Estilo.TEXTO)
        if (sub != null) t.sub.text = sub.ifEmpty { " " }
    }

    private fun pintaScanner(valor: String, cor: Int, sub: String) = pinta(tileScanner, valor, cor, sub)

    private fun mostrarGnss(g: Gga, texto: String) {
        val cor = when (g.qualidade) { 4 -> Estilo.OK; 5, 2 -> Estilo.ATENCAO; 0 -> Estilo.ERRO; else -> Estilo.NEUTRO }
        val idade = g.idadeCorrecao?.let { " · corr. %.0f s".format(it) } ?: ""
        pinta(tileGnss, g.nomeSolucao(), cor, "${g.satelites} sat" + (g.hdop?.let { " · HDOP %.1f".format(it).replace('.', ',') } ?: "") + idade)
        Gst.ler(texto)?.let { ultimoGst = it; ultimoGstMs = System.currentTimeMillis() }
        val gst = if (System.currentTimeMillis() - ultimoGstMs < 5000) ultimoGst else null
        val p = Precisao.de(g, gst)
        pinta(tilePrecisao, p.valor, when (p.nivel) { 2 -> Estilo.OK; 1 -> Estilo.ATENCAO; 0 -> Estilo.ERRO; else -> Estilo.NEUTRO }, p.sub)
    }

    private fun atualizarTempo() {
        if (!::tvTempo.isInitialized) return
        if (levantando) {
            val s = (System.currentTimeMillis() - inicioLevantamentoMs) / 1000
            tvTempo.text = "⏱ %02d:%02d:%02d   ·   %.0f m andados".format(s / 3600, s / 60 % 60, s % 60, monitor.situacao.distanciaTotal)
            tvTempo.setTextColor(Estilo.TEXTO)
        } else {
            tvTempo.text = "Levantamento parado"; tvTempo.setTextColor(Estilo.TEXTO_2)
        }
    }

    private fun aoTeclado(visivel: Boolean) {
        tecladoVisivel = visivel
        val v = if (visivel) View.GONE else View.VISIBLE
        barraAbas.visibility = v; barraAcao.visibility = v; gradeTiles.visibility = v
    }

    // ------------------------------------------------------------------ preparo do scanner
    /**
     * O que falta ANTES de iniciar. Como no FireEye, a checagem do lidar e das câmeras só vale depois de iniciar; antes
     * contam: cartão de memória, parâmetros, calibração, sistema principal e bateria.
     */
    private fun pendenciasDoScanner(): List<String> {
        val l = ArrayList<String>()
        if (memoriaTotal == 0 || (checagemRecebida && checagem[0] == 0))
            l.add("CARTÃO DE MEMÓRIA ausente ou com problema (coloque o cartão e reinicie o scanner)")
        if (!paramsOk) l.add("parâmetros de inicialização (ainda não chegaram)")
        if (!csvOk) l.add("calibração do lidar (ainda não chegou)")
        if (checagemRecebida) for (i in listOf(2, 3)) if (checagem[i] != 1) l.add("checagem: ${Protocolo.ITENS_CHECAGEM[i].lowercase()} ainda não está OK")
        return l
    }

    private fun subPreparo(): String = if (pendenciasDoScanner().isEmpty() && paramsOk) "pronto para iniciar" else "preparando…"

    private fun atualizarPreparo() {
        if (!::tvPreparo.isInitialized) return
        fun mk(ok: Boolean, t: String) = (if (ok) "✔ " else "… ") + t
        val cartao = if (memoriaTotal == 0 || (checagemRecebida && checagem[0] == 0)) "✘ cartão de memória" else mk(checagemRecebida && checagem[0] == 1 || memoriaTotal > 0, "cartão de memória")
        val partes = arrayListOf(mk(paramsOk, "parâmetros"), mk(csvOk, "calibração"), cartao)
        if (checagemRecebida) { partes.add(mk(checagem[2] == 1, "sistema principal")); partes.add(mk(checagem[3] == 1, "bateria")) }
        else partes.add("… checagem do sistema")
        if (firmware.isNotEmpty()) partes.add("firmware $firmware")
        val depois = if (!levantando) "Lidar e câmeras: checados depois de iniciar." else
            "Lidar e câmeras: " + (if (checagemRecebida) mk(checagem[1] == 1, "lidar") + "  " + mk(checagem[4] == 1 && checagem[5] == 1, "câmeras") else "aguardando checagem")
        tvPreparo.text = "Antes de iniciar: " + partes.joinToString("   ") + "\n" + depois
        tvPreparo.setTextColor(if (pendenciasDoScanner().isEmpty() && (checagemRecebida || paramsOk)) Estilo.OK else Estilo.TEXTO_2)
        if (equip.conectado) pintaScanner("Conectado", Estilo.OK, subPreparo())
    }

    /** Texto enquanto o levantamento foi pedido mas nenhum quadro de nuvem chegou ainda. */
    private fun textoAguardandoInicio(): String {
        val agora = System.currentTimeMillis()
        val seg = (agora - inicioEnviadoMs) / 1000
        val pend = pendenciasDoScanner()
        val extra = if (pend.isNotEmpty()) "\nO scanner ainda não terminou de se preparar: " + pend.joinToString("; ") + "." else ""
        return when {
            respostaInicioMs == 0L && seg < 8 -> "Comando de início enviado; aguardando o scanner confirmar ($seg s)…"
            respostaInicioMs == 0L -> "O scanner NÃO confirmou o início em $seg s. Toque em Parar levantamento, espere o scanner terminar de ligar/checar e tente de novo.$extra"
            (agora - respostaInicioMs) / 1000 < 45 -> "Scanner confirmou. Iniciando o SLAM: fique parado e aguarde (${(agora - respostaInicioMs) / 1000} s)…"
            else -> "Sem quadros há ${(agora - respostaInicioMs) / 1000} s depois da confirmação. Pare, confira o lidar e a checagem do scanner e tente de novo.$extra"
        }
    }

    // ------------------------------------------------------------------ SLAM
    private fun mostrarSlam(s: MonitorSlam.Situacao) {
        if (levantando && monitor.totalQuadros == 0) {                      // ainda sem nuvem: mostra em que etapa o início está
            val pedido = textoAguardandoInicio()
            val ruim = pedido.startsWith("O scanner NÃO") || pedido.startsWith("Sem quadros")
            faixaSlam.background = Estilo.forma(this, if (ruim) Estilo.ATENCAO else Estilo.SUPERFICIE_2, 18)
            tvSlamTitulo.text = if (ruim) "Scanner não iniciou" else "Iniciando o levantamento…"
            tvSlamTitulo.setTextColor(if (ruim) Color.parseColor("#1A1200") else Estilo.TEXTO)
            tvSlamMotivo.text = pedido; tvSlamMotivo.setTextColor(if (ruim) Color.parseColor("#1A1200") else Estilo.TEXTO_2)
            numVel.valor.text = "—"; numPontos.valor.text = "—"; numQuadros.valor.text = "0,0 /s"; numAndado.valor.text = "0 m"
            return
        }
        val (fundo, texto, titulo) = when (s.nivel) {
            MonitorSlam.Nivel.PARADO -> Triple(Estilo.SUPERFICIE, Estilo.TEXTO_2, "SLAM parado")
            MonitorSlam.Nivel.OK -> Triple(Estilo.comAlfa(Estilo.OK, 46), Estilo.TEXTO, "SLAM normal")
            MonitorSlam.Nivel.ATENCAO -> Triple(Estilo.ATENCAO, Color.parseColor("#1A1200"), "Atenção: o SLAM pode se perder")
            MonitorSlam.Nivel.PERDIDO -> Triple(Estilo.ERRO, Color.WHITE, "SLAM perdido")
        }
        faixaSlam.background = Estilo.forma(this, fundo, 18, if (s.nivel == MonitorSlam.Nivel.OK) 1 else 0, Estilo.comAlfa(Estilo.OK, 120))
        tvSlamTitulo.text = titulo; tvSlamTitulo.setTextColor(texto)
        tvSlamMotivo.setTextColor(if (s.nivel == MonitorSlam.Nivel.PARADO || s.nivel == MonitorSlam.Nivel.OK) Estilo.TEXTO_2 else texto)
        tvSlamMotivo.text = when {
            s.nivel == MonitorSlam.Nivel.PARADO -> "Toque aqui para silenciar o alarme por 1 minuto"
            else -> s.motivo
        }
        numVel.valor.text = "%.1f m/s".format(s.velocidade)
        numPontos.valor.text = if (s.pontos > 0) "%,d".format(s.pontos).replace(',', '.') else "—"
        numQuadros.valor.text = "%.1f /s".format(s.quadrosPorS)
        numAndado.valor.text = "%.0f m".format(s.distanciaTotal)
        alarme(s.nivel)
    }

    private fun alarme(n: MonitorSlam.Nivel) {
        val agora = System.currentTimeMillis()
        if (agora < silenciadoAte) return
        val intervalo = when (n) { MonitorSlam.Nivel.PERDIDO -> 2000L; MonitorSlam.Nivel.ATENCAO -> 4000L; else -> return }
        if (agora - ultimoAlarme < intervalo) return
        ultimoAlarme = agora
        if (n == MonitorSlam.Nivel.PERDIDO) { apito(3); vibrar(longArrayOf(0, 500, 200, 500, 200, 500)) }
        else { apito(1); vibrar(longArrayOf(0, 300)) }
    }

    private fun apito(vezes: Int) {
        for (i in 0 until vezes) ui.postDelayed({ tom.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250) }, i * 350L)
    }

    private fun vibrar(padrao: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createWaveform(padrao, -1))
    }

    // ------------------------------------------------------------------ RTK
    private fun cfgRtk(): Ntrip.Config = Ntrip.Config(
        etHost.edit.text.toString().trim(), etPorta.edit.text.toString().trim().toIntOrNull() ?: -1,
        etUsuario.edit.text.toString().trim(), etSenha.edit.text.toString(), etMount.edit.text.toString().trim(),
    )

    private fun salvarRtk(c: Ntrip.Config) {
        prefs.edit().putString("rtk_host", c.host).putInt("rtk_porta", c.porta).putString("rtk_usuario", c.usuario)
            .putString("rtk_senha", c.senha).putString("rtk_mount", c.mountpoint).apply()
    }

    private fun obterMountpoints() {
        esconderTeclado()
        val c = cfgRtk()
        btObter.isEnabled = false; btObter.alpha = 0.5f
        statusRtk("Pedindo a lista ao caster…", Estilo.TEXTO_2)
        trabalho.execute {
            try {
                val lista = ntrip.obterMountpoints(c)
                ui.post {
                    btObter.isEnabled = true; btObter.alpha = 1f
                    mountpoints = lista
                    salvarRtk(c.copy(mountpoint = etMount.edit.text.toString().trim()))
                    statusRtk("${lista.size} mountpoint(s) no caster. Escolha um.", Estilo.OK)
                    escolherMountpoint()
                }
            } catch (e: Exception) {
                ui.post { btObter.isEnabled = true; btObter.alpha = 1f; statusRtk("Não consegui a lista: ${e.message}", Estilo.ERRO) }
            }
        }
    }

    private fun escolherMountpoint() {
        if (mountpoints.isEmpty()) { obterMountpoints(); return }
        val nomes = mountpoints.map { it.descricao() }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Mountpoints do caster")
            .setItems(nomes) { _, i -> etMount.edit.setText(mountpoints[i].nome) }   // só troca quando VOCÊ escolhe
            .setNegativeButton("Fechar", null).show()
    }

    private fun alternarRtk() {
        esconderTeclado()
        if (ntrip.conectado) { ntrip.desconectar(); return }
        val c = cfgRtk()
        try {
            salvarRtk(c)
            ntrip.conectar(c)
            btConectarRtk.text = "Desconectar"; Estilo.aplicarTipo(this, btConectarRtk, Estilo.TipoBotao.PERIGO)
        } catch (e: Exception) {
            statusRtk(e.message ?: "erro", Estilo.ERRO)
        }
    }

    private fun statusRtk(t: String, cor: Int) {
        tvRtk.text = t; tvRtk.setTextColor(cor)
    }

    private fun mostrarRtk(est: Ntrip.Estado, txt: String) {
        statusRtk(txt, when (est) { Ntrip.Estado.RECEBENDO -> Estilo.OK; Ntrip.Estado.ERRO -> Estilo.ERRO; else -> Estilo.TEXTO_2 })
        val (rotulo, cor) = when (est) {
            Ntrip.Estado.RECEBENDO -> "Recebendo" to Estilo.OK
            Ntrip.Estado.CONECTANDO -> "Conectando…" to Estilo.ATENCAO
            Ntrip.Estado.ERRO -> "Com erro" to Estilo.ERRO
            Ntrip.Estado.DESLIGADO -> "Desligado" to Estilo.NEUTRO
        }
        pinta(tileRtk, rotulo, cor, if (est == Ntrip.Estado.ERRO) txt.take(34) else if (est == Ntrip.Estado.DESLIGADO) "caster NTRIP" else "")
        val on = ntrip.conectado
        btConectarRtk.text = if (on) "Desconectar" else "Conectar"
        Estilo.aplicarTipo(this, btConectarRtk, if (on) Estilo.TipoBotao.PERIGO else Estilo.TipoBotao.SUCESSO)
    }

    private fun atualizarRtkContadores() {
        if (!ntrip.conectado || ntrip.ultimoDadoMs == 0L) return
        val idade = (System.currentTimeMillis() - ntrip.ultimoDadoMs) / 1000.0
        statusRtk("Recebendo correção · %.1f kB · último dado há %.0f s".format(ntrip.bytesRtcm / 1024.0, idade),
            if (idade < 5) Estilo.OK else Estilo.ATENCAO)
        pinta(tileRtk, if (idade < 5) "Recebendo" else "Sem dados novos", if (idade < 5) Estilo.OK else Estilo.ATENCAO,
            "há %.0f s · %.0f kB".format(idade, ntrip.bytesRtcm / 1024.0))
    }

    // ------------------------------------------------------------------ tela
    private fun montarTela() {
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Estilo.FUNDO) }

        // ---- topo fixo: SLAM + grade de estado (sempre à vista)
        val topo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(2)) }
        faixaSlam = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(9), dp(14), dp(9))
            background = Estilo.forma(this@MainActivity, Estilo.SUPERFICIE, 16)
            setOnClickListener { silenciadoAte = System.currentTimeMillis() + 60_000; toast("Alarme silenciado por 1 minuto") }
        }
        tvSlamTitulo = Estilo.texto(this, "SLAM parado", 18f, Estilo.TEXTO_2, true)
        tvSlamMotivo = Estilo.texto(this, "Toque aqui para silenciar o alarme por 1 minuto", 12f, Estilo.TEXTO_2).apply { maxLines = 3 }
        faixaSlam.addView(tvSlamTitulo); faixaSlam.addView(tvSlamMotivo)
        topo.addView(faixaSlam)

        tileScanner = criarTile("Scanner"); tileBateria = criarTile("Bateria"); tileMemoria = criarTile("Memória")
        tileGnss = criarTile("GNSS"); tileRtk = criarTile("RTK (caster)"); tilePrecisao = criarTile("Precisão")
        pintaScanner("Desconectado", Estilo.NEUTRO, "toque em Conectar")
        pinta(tileBateria, "—", Estilo.NEUTRO, "aguardando"); pinta(tileMemoria, "—", Estilo.NEUTRO, "aguardando")
        pinta(tileGnss, "Aguardando", Estilo.NEUTRO, "sem posição ainda"); pinta(tileRtk, "Desligado", Estilo.NEUTRO, "caster NTRIP")
        pinta(tilePrecisao, "—", Estilo.NEUTRO, "sem posição")
        gradeTiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(2)) }
        fun linhaTiles(a: Tile, b: Tile, c: Tile) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((k, t) in listOf(a, b, c).withIndex())
                addView(t.bloco, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (k < 2) rightMargin = dp(6) })
        }
        gradeTiles.addView(linhaTiles(tileScanner, tileBateria, tileMemoria))
        gradeTiles.addView(Estilo.espaco(this, 6))
        gradeTiles.addView(linhaTiles(tileGnss, tileRtk, tilePrecisao))
        topo.addView(gradeTiles)
        raiz.addView(topo)

        // ---- abas
        barraAbas = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        btAbaLev = botaoAba("Levantamento") { mostrarAba(false) }
        btAbaRtk = botaoAba("RTK") { mostrarAba(true) }
        barraAbas.addView(btAbaLev, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        barraAbas.addView(btAbaRtk, LinearLayout.LayoutParams(0, dp(44), 1f))
        raiz.addView(barraAbas)

        val conteudo = FrameLayout(this)
        abaLevantamento = montarAbaLevantamento(); abaRtk = montarAbaRtk()
        conteudo.addView(abaLevantamento); conteudo.addView(abaRtk)
        raiz.addView(conteudo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- barra de ação fixa: tempo, iniciar/parar e ponto de controle sempre à mão
        barraAcao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(10))
            setBackgroundColor(Estilo.SUPERFICIE)
        }
        tvTempo = Estilo.texto(this, "Levantamento parado", 13f, Estilo.TEXTO_2, true)
        barraAcao.addView(tvTempo)
        val linhaBotoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        btIniciar = Estilo.botao(this, "Iniciar levantamento", Estilo.TipoBotao.SUCESSO) { alternarLevantamento() }
        btPonto = Estilo.botao(this, "Ponto de controle", Estilo.TipoBotao.CONTORNO) {
            if (!levantando) toast("Inicie o levantamento antes")
            else AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Gravar ponto de controle aqui?")
                .setPositiveButton("Gravar") { _, _ -> equip.enviar(Protocolo.PONTO_CONTROLE) }
                .setNegativeButton("Cancelar", null).show()
        }
        linhaBotoes.addView(btIniciar, LinearLayout.LayoutParams(0, dp(54), 1.6f).apply { rightMargin = dp(8) })
        linhaBotoes.addView(btPonto, LinearLayout.LayoutParams(0, dp(54), 1f))
        barraAcao.addView(linhaBotoes)
        raiz.addView(barraAcao)

        setContentView(raiz)
        mostrarAba(false)
        ajustarInsets(raiz)
    }

    /**
     * Android 15 desenha o app por baixo das barras do sistema E do teclado (a tela "invade" o teclado). Aqui o espaço
     * do teclado vira margem de baixo da tela: a lista de campos encolhe e o campo em foco continua à vista.
     */
    private fun ajustarInsets(raiz: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            raiz.setOnApplyWindowInsetsListener { v, ins ->
                val barras = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = ins.getInsets(WindowInsets.Type.ime())
                v.setPadding(barras.left, barras.top, barras.right, max(barras.bottom, ime.bottom))
                val vis = ime.bottom > 0
                if (vis != tecladoVisivel) aoTeclado(vis)
                ins
            }
            raiz.requestApplyInsets()
        }
        // abaixo do Android 11 o próprio adjustResize já encolhe a tela; só escondemos as abas quando o teclado abre
        raiz.viewTreeObserver.addOnGlobalLayoutListener {
            if (Build.VERSION.SDK_INT < 30) {
                val r = android.graphics.Rect(); raiz.getWindowVisibleDisplayFrame(r)
                val vis = raiz.rootView.height - r.bottom > raiz.rootView.height * 0.15
                if (vis != tecladoVisivel) aoTeclado(vis)
            }
        }
    }

    private fun montarAbaLevantamento(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(24)) }

        val cartaoScanner = Estilo.cartao(this)
        cartaoScanner.addView(Estilo.tituloCartao(this, "Scanner"))
        btConectarScanner = Estilo.botao(this, "Conectar ao scanner", Estilo.TipoBotao.CHEIO) { botaoConectarScanner() }
        cartaoScanner.addView(btConectarScanner)
        tvScanner = Estilo.texto(this, "Ligue o scanner, deixe a Localização e o Wi-Fi do celular ligados e toque em Conectar: o app procura a rede do scanner (nome ${ConectorScanner.PREFIXO}…) e conecta sozinho.", 14f, Estilo.TEXTO_2)
            .apply { setPadding(dp(2), dp(12), dp(2), 0) }
        cartaoScanner.addView(tvScanner)
        tvPreparo = Estilo.texto(this, "Preparo do scanner: aparece depois de conectar.", 13f, Estilo.TEXTO_2).apply { setPadding(dp(2), dp(10), dp(2), 0) }
        cartaoScanner.addView(tvPreparo)
        col.addView(cartaoScanner)

        val cartaoNumeros = Estilo.cartao(this)
        cartaoNumeros.addView(Estilo.tituloCartao(this, "Qualidade do SLAM"))
        numVel = Estilo.numero(this, "velocidade"); numPontos = Estilo.numero(this, "pontos no quadro")
        numQuadros = Estilo.numero(this, "quadros por segundo"); numAndado = Estilo.numero(this, "distância andada")
        fun linha(a: Estilo.Numero, b: Estilo.Numero) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a.bloco, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            addView(b.bloco, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        cartaoNumeros.addView(linha(numVel, numPontos))
        cartaoNumeros.addView(Estilo.espaco(this, 8))
        cartaoNumeros.addView(linha(numQuadros, numAndado))
        col.addView(cartaoNumeros)

        val cartaoMapa = Estilo.cartao(this)
        cartaoMapa.addView(Estilo.tituloCartao(this, "Caminho percorrido"))
        vista = VistaTopo(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat()) }
            }
        }
        cartaoMapa.addView(vista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        col.addView(cartaoMapa)

        val cartaoLev = Estilo.cartao(this)
        cartaoLev.addView(Estilo.tituloCartao(this, "Levantamento"))
        etProjeto = Estilo.campo(this, "Nome do projeto", prefs.getString("projeto", "") ?: "", dica = "ex.: rua_matriz_1")
        etProjeto.edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        cartaoLev.addView(etProjeto.bloco)
        cartaoLev.addView(Estilo.texto(this, "Iniciar, parar e gravar ponto de controle: botões fixos no rodapé da tela.", 13f, Estilo.TEXTO_2))
        col.addView(cartaoLev)

        return ScrollView(this).apply { isFillViewport = false; addView(col); clipToPadding = false }.also { sv ->
            focoVisivel(sv, listOf(etProjeto))
        }
    }

    private fun montarAbaRtk(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(24)) }

        val cartaoCaster = Estilo.cartao(this)
        cartaoCaster.addView(Estilo.tituloCartao(this, "Caster NTRIP"))
        etHost = Estilo.campo(this, "Endereço do caster", prefs.getString("rtk_host", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "ex.: rtk.empresa.com.br ou 200.10.20.30")
        etPorta = Estilo.campo(this, "Porta", prefs.getInt("rtk_porta", 2101).takeIf { it > 0 }?.toString() ?: "", InputType.TYPE_CLASS_NUMBER, "2101")
        etUsuario = Estilo.campo(this, "Usuário (se o caster pedir)", prefs.getString("rtk_usuario", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        etSenha = Estilo.campo(this, "Senha (se o caster pedir)", prefs.getString("rtk_senha", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        etSenha.edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val verSenha = Estilo.texto(this, "Mostrar", 13f, Estilo.PRIMARIA, true).apply {
            setPadding(dp(8), dp(4), dp(2), dp(6)); gravity = Gravity.END
            setOnClickListener {
                senhaVisivel = !senhaVisivel
                val pos = etSenha.edit.selectionEnd
                etSenha.edit.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (senhaVisivel) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                etSenha.edit.setSelection(pos.coerceAtLeast(0)); text = if (senhaVisivel) "Ocultar" else "Mostrar"
            }
        }
        // rótulo da senha com o "Mostrar" à direita
        (etSenha.bloco.getChildAt(0) as TextView).let { rot ->
            etSenha.bloco.removeViewAt(0)
            val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            linha.addView(rot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            linha.addView(verSenha)
            etSenha.bloco.addView(linha, 0)
        }
        for (c in listOf(etHost, etPorta, etUsuario, etSenha)) cartaoCaster.addView(c.bloco)
        col.addView(cartaoCaster)

        val cartaoMount = Estilo.cartao(this)
        cartaoMount.addView(Estilo.tituloCartao(this, "Mountpoint"))
        btObter = Estilo.botao(this, "Obter mountpoints", Estilo.TipoBotao.CONTORNO) { obterMountpoints() }
        cartaoMount.addView(btObter)
        cartaoMount.addView(Estilo.espaco(this, 12))
        etMount = Estilo.campo(this, "Mountpoint escolhido", prefs.getString("rtk_mount", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, "toque em \"Obter mountpoints\" ou digite")
        etMount.edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        cartaoMount.addView(etMount.bloco)
        cartaoMount.addView(Estilo.botao(this, "Ver lista de novo", Estilo.TipoBotao.CONTORNO) { escolherMountpoint() })
        col.addView(cartaoMount)

        val cartaoCon = Estilo.cartao(this)
        cartaoCon.addView(Estilo.tituloCartao(this, "Conexão"))
        btConectarRtk = Estilo.botao(this, "Conectar", Estilo.TipoBotao.SUCESSO) { alternarRtk() }
        cartaoCon.addView(btConectarRtk)
        tvRtk = Estilo.texto(this, "RTK desligado. Preencha o caster, escolha o mountpoint e toque em Conectar.", 14f, Estilo.TEXTO_2)
            .apply { setPadding(dp(2), dp(12), dp(2), 0) }
        cartaoCon.addView(tvRtk)
        col.addView(cartaoCon)

        return ScrollView(this).apply { addView(col); clipToPadding = false }.also { sv ->
            focoVisivel(sv, listOf(etHost, etPorta, etUsuario, etSenha, etMount))
        }
    }

    /** Ao tocar num campo, rola a lista para o campo ficar no alto da área visível (acima do teclado), com o rótulo. */
    private fun focoVisivel(sv: ScrollView, campos: List<Estilo.Campo>) {
        for (c in campos) {
            c.edit.setOnFocusChangeListener { _, tem ->
                if (tem) ui.postDelayed({
                    var y = 0; var v: View = c.bloco
                    while (v !== sv.getChildAt(0) && v.parent is View) { y += v.top; v = v.parent as View }
                    sv.smoothScrollTo(0, max(0, y - dp(90)))
                }, 250)
            }
        }
    }

    private fun mostrarAba(rtk: Boolean) {
        abaLevantamento.visibility = if (rtk) View.GONE else View.VISIBLE
        abaRtk.visibility = if (rtk) View.VISIBLE else View.GONE
        pintarAba(btAbaLev, !rtk); pintarAba(btAbaRtk, rtk)
        esconderTeclado()
    }

    private fun botaoAba(t: String, acao: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; stateListAnimator = null; elevation = 0f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setOnClickListener { acao() }
    }

    private fun pintarAba(b: Button, ativa: Boolean) {
        b.background = Estilo.comToque(this, Estilo.forma(this, if (ativa) Estilo.PRIMARIA else Estilo.SUPERFICIE, 12,
            if (ativa) 0 else 1), 12)
        b.setTextColor(if (ativa) Color.WHITE else Estilo.TEXTO_2)
    }

    private fun esconderTeclado() {
        val v = currentFocus ?: return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()
}
