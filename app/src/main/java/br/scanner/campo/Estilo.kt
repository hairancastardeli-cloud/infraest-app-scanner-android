package br.scanner.campo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Cores, medidas e peças visuais do app (tema escuro, cantos arredondados, alvos de toque grandes para campo). */
object Estilo {
    val FUNDO = Color.parseColor("#0E1014")
    val SUPERFICIE = Color.parseColor("#171A20")
    val SUPERFICIE_2 = Color.parseColor("#1F232B")
    val BORDA = Color.parseColor("#2A2F39")
    val TEXTO = Color.parseColor("#ECEEF1")
    val TEXTO_2 = Color.parseColor("#9AA1AC")
    val TEXTO_3 = Color.parseColor("#6B7280")
    val PRIMARIA = Color.parseColor("#3B82F6")
    val OK = Color.parseColor("#22C55E")
    val ATENCAO = Color.parseColor("#F59E0B")
    val ERRO = Color.parseColor("#EF4444")
    val NEUTRO = Color.parseColor("#6B7280")

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

    fun comAlfa(cor: Int, alfa: Int) = Color.argb(alfa, Color.red(cor), Color.green(cor), Color.blue(cor))

    fun forma(ctx: Context, cor: Int, raio: Int = 16, borda: Int = 0, corBorda: Int = BORDA): GradientDrawable =
        GradientDrawable().apply {
            setColor(cor); cornerRadius = ctx.dpf(raio.toFloat())
            if (borda > 0) setStroke(ctx.dp(borda), corBorda)
        }

    fun comToque(ctx: Context, fundo: Drawable, raio: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(comAlfa(Color.WHITE, 40)), fundo, forma(ctx, Color.WHITE, raio))

    /** Cartão: superfície arredondada com borda discreta. */
    fun cartao(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = forma(ctx, SUPERFICIE, 18, 1)
        with(ctx) { setPadding(dp(16), dp(14), dp(16), dp(16)) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { with(ctx) { bottomMargin = dp(12) } }
    }

    fun texto(ctx: Context, t: String, tam: Float = 15f, cor: Int = TEXTO, negrito: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t; textSize = tam; setTextColor(cor)
            if (negrito) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.1f)
        }

    fun tituloCartao(ctx: Context, t: String): TextView = texto(ctx, t.uppercase(), 12f, TEXTO_2, true).apply {
        letterSpacing = 0.08f; with(ctx) { setPadding(0, 0, 0, dp(10)) }
    }

    /** Campo com rótulo em cima (o rótulo continua visível enquanto se digita) e borda azul quando em foco. */
    class Campo(val bloco: LinearLayout, val edit: EditText)

    fun campo(ctx: Context, rotulo: String, valor: String, tipo: Int = InputType.TYPE_CLASS_TEXT, dica: String = ""): Campo {
        val bloco = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { with(ctx) { bottomMargin = dp(12) } }
        }
        bloco.addView(texto(ctx, rotulo, 13f, TEXTO_2).apply { with(ctx) { setPadding(dp(2), 0, 0, dp(6)) } })
        val e = EditText(ctx).apply {
            setText(valor); hint = dica; inputType = tipo; isSingleLine = true
            textSize = 17f; setTextColor(TEXTO); setHintTextColor(TEXTO_3)
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), forma(ctx, SUPERFICIE_2, 12, 2, PRIMARIA))
                addState(intArrayOf(), forma(ctx, SUPERFICIE_2, 12, 1, BORDA))
            }
            with(ctx) { setPadding(dp(14), dp(12), dp(14), dp(12)); minHeight = dp(52) }
        }
        bloco.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Campo(bloco, e)
    }

    enum class TipoBotao { CHEIO, CONTORNO, PERIGO, SUCESSO }

    fun botao(ctx: Context, t: String, tipo: TipoBotao = TipoBotao.CHEIO, acao: () -> Unit): Button = Button(ctx).apply {
        text = t; isAllCaps = false; textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null; elevation = 0f
        aplicarTipo(ctx, this, tipo)
        with(ctx) {
            minHeight = dp(54); setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(4) }
        }
        setOnClickListener { acao() }
    }

    fun aplicarTipo(ctx: Context, b: Button, tipo: TipoBotao) {
        val (fundo, cor) = when (tipo) {
            TipoBotao.CHEIO -> forma(ctx, PRIMARIA, 14) to Color.WHITE
            TipoBotao.SUCESSO -> forma(ctx, OK, 14) to Color.parseColor("#06210F")
            TipoBotao.PERIGO -> forma(ctx, ERRO, 14) to Color.WHITE
            TipoBotao.CONTORNO -> forma(ctx, Color.TRANSPARENT, 14, 1, comAlfa(PRIMARIA, 200)) to Color.parseColor("#93B8FA")
        }
        b.background = comToque(ctx, fundo, 14); b.setTextColor(cor)
    }

    /** Etiqueta pequena com bolinha colorida (estado do scanner, GNSS, RTK). */
    fun etiqueta(ctx: Context): TextView = TextView(ctx).apply {
        textSize = 13f; setTextColor(TEXTO); gravity = Gravity.CENTER_VERTICAL; isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        with(ctx) { setPadding(dp(10), dp(6), dp(12), dp(6)); compoundDrawablePadding = dp(8) }
    }

    fun pintarEtiqueta(ctx: Context, v: TextView, t: String, cor: Int) {
        v.text = t
        v.background = forma(ctx, comAlfa(cor, 38), 50)
        val bola = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cor); with(ctx) { setSize(dp(8), dp(8)) } }
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(bola, null, null, null)
    }

    /** Bloco de número grande com legenda (painel do SLAM). */
    class Numero(val bloco: LinearLayout, val valor: TextView)

    fun numero(ctx: Context, legenda: String): Numero {
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = forma(ctx, comAlfa(Color.BLACK, 70), 12)
            with(ctx) { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        }
        val v = texto(ctx, "—", 20f, TEXTO, true)
        b.addView(v); b.addView(texto(ctx, legenda, 12f, TEXTO_2))
        return Numero(b, v)
    }

    fun espaco(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, with(ctx) { dp(h) })
    }
}
