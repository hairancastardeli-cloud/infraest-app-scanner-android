package br.scanner.campo

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import br.scanner.campo.Estilo.dp
import java.io.File
import java.util.concurrent.Executors

/** Nuvem acumulada do levantamento atual (o visualizador ao vivo lê daqui). */
object NuvemAtual {
    val nuvem = NuvemAcumulada()
    @Volatile var nome = ""
}

/**
 * Tela cheia com a nuvem em 3D. Extra "arquivo" = abre um nuvem_*.bin gravado; sem ele mostra o levantamento em
 * andamento e atualiza a cada 2 s.
 */
class Nuvem3DActivity : Activity() {
    private lateinit var vista: Visualizador3D
    private lateinit var info: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newSingleThreadExecutor()
    private var aoVivo = false
    private var versaoMostrada = -1
    private var primeira = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Estilo.FUNDO; window.navigationBarColor = Estilo.FUNDO
        val raiz = FrameLayout(this).apply { setBackgroundColor(Estilo.FUNDO) }
        vista = Visualizador3D(this)
        raiz.addView(vista, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val topo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundColor(Estilo.comAlfa(Estilo.FUNDO, 200))
        }
        val arq = intent.getStringExtra("arquivo")
        aoVivo = arq == null
        val titulo = if (aoVivo) "Ao vivo: ${NuvemAtual.nome}" else File(arq!!).name.removePrefix("nuvem_").removeSuffix(".bin")
        topo.addView(Estilo.texto(this, titulo, 16f, Estilo.TEXTO, true))
        info = Estilo.texto(this, "carregando…", 12f, Estilo.TEXTO_2)
        topo.addView(info)
        raiz.addView(topo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(12))
            setBackgroundColor(Estilo.comAlfa(Estilo.FUNDO, 200))
        }
        fun bt(t: String, f: () -> Unit) = Estilo.botao(this, t, Estilo.TipoBotao.CONTORNO, f).also {
            it.textSize = 14f; it.isSingleLine = true; it.minHeight = 0; it.minWidth = 0; it.setPadding(dp(2), 0, dp(2), 0)
            it.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            barra.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        }
        bt("Inicial") { vista.vistaInicial() }
        bt("Topo") { vista.vistaTopo() }
        bt("Ponto+") { vista.tamanhoPonto = (vista.tamanhoPonto + 1f).coerceAtMost(8f) }
        bt("Ponto−") { vista.tamanhoPonto = (vista.tamanhoPonto - 1f).coerceAtLeast(1f) }
        bt("Fechar") { finish() }
        raiz.addView(barra, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(raiz)
        // Android 15 desenha por baixo das barras do sistema: o título desce abaixo do relógio e os botões sobem acima da
        // barra de navegação (a nuvem continua ocupando a tela toda)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            raiz.setOnApplyWindowInsetsListener { _, ins ->
                val s = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                topo.setPadding(dp(14) + s.left, dp(10) + s.top, dp(14) + s.right, dp(8))
                barra.setPadding(dp(10) + s.left, dp(8), dp(10) + s.right, dp(12) + s.bottom)
                ins
            }
            raiz.requestApplyInsets()
        }

        if (aoVivo) atualizarAoVivo()
        else trabalho.execute {
            try {
                val xyz = NuvemAcumulada.ler(File(arq!!))
                ui.post { mostrar(xyz, true) }
            } catch (e: Exception) {
                ui.post { info.text = "Não abriu: ${e.message}"; info.setTextColor(Estilo.ERRO) }
            }
        }
    }

    private fun mostrar(xyz: FloatArray, reenquadrar: Boolean) {
        vista.definirPontos(xyz, reenquadrar)
        val n = xyz.size / 3
        info.text = "%,d pontos (1 a cada 10 cm) · 1 dedo gira · 2 dedos movem e dão zoom · toque duplo volta".format(n).replace(',', '.') +
            if (aoVivo) " · atualiza sozinho" else ""
        info.setTextColor(Estilo.TEXTO_2)
    }

    private fun atualizarAoVivo() {
        val nv = NuvemAtual.nuvem
        if (nv.versao != versaoMostrada) {
            versaoMostrada = nv.versao
            val xyz = nv.copia()
            if (xyz.isNotEmpty()) { mostrar(xyz, primeira); primeira = false }
            else info.text = "Ainda sem pontos: inicie o levantamento e aguarde os quadros."
        }
        if (!isFinishing) ui.postDelayed({ atualizarAoVivo() }, 2000)
    }

    override fun onResume() { super.onResume(); vista.onResume() }
    override fun onPause() { vista.onPause(); super.onPause() }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); trabalho.shutdown(); super.onDestroy() }

}
