package br.scanner.campo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.scanner.campo.Estilo.dp
import java.io.File

/**
 * Aba GNSS: cadastro de pontos só com o RTK do scanner (ver [PontoGnss]). "Ligar o GNSS" liga o receptor SEM iniciar o
 * levantamento ([AuxiliarGnss], pelo adb); se um levantamento estiver rodando, a posição vem dele.
 * Cada ponto = leitura 1 + leitura 2 com o scanner girado 180° em volta da ponta.
 */
class AbaGnss(
    private val act: Activity,
    private val prefs: SharedPreferences,
    private val pasta: File,
    private val ligarGnss: () -> Unit,
    private val desligarGnss: () -> Unit,
    private val gnssLigado: () -> Boolean,
    private val apito: (Int) -> Unit,
) {
    /** Texto do auxiliar do GNSS (instalando, ligado, erro…), mostrado na situação. */
    @Volatile var estadoAuxiliar = ""
    private enum class Etapa { PARADO, LEITURA1, GIRAR, LEITURA2 }

    @Volatile private var ultimoGga: Gga? = null
    @Volatile private var ultimoGgaMs = 0L
    @Volatile private var coletor: PontoGnss.Coletor? = null
    private var etapa = Etapa.PARADO
    private var leitura1: PontoGnss.Leitura? = null
    private var suporte = PontoGnss.Suporte.de(prefs.getString("gnss_suporte", null))

    private lateinit var tvSituacao: TextView
    private lateinit var btLigar: Button
    private lateinit var btDesligar: Button
    private lateinit var etObra: Estilo.Campo
    private lateinit var tvArquivo: TextView
    private lateinit var btBraco: Button
    private lateinit var btBastao: Button
    private lateinit var blocoBraco: LinearLayout
    private lateinit var blocoBastao: LinearLayout
    private lateinit var etBraco: Estilo.Campo
    private lateinit var etBastao: Estilo.Campo
    private lateinit var etRosca: Estilo.Campo
    private lateinit var etNome: Estilo.Campo
    private lateinit var etDesc: Estilo.Campo
    private lateinit var etTempo: Estilo.Campo
    private lateinit var cbFlutuante: CheckBox
    private lateinit var btMedir: Button
    private lateinit var btCancelar: Button
    private lateinit var tvMedicao: TextView
    private lateinit var lista: LinearLayout
    lateinit var campos: List<Estilo.Campo>
        private set

    /** Chamado na thread do socket a cada GGA lido. */
    fun aoGga(g: Gga) {
        ultimoGga = g; ultimoGgaMs = System.currentTimeMillis()
        coletor?.adicionar(g)
        act.runOnUiThread { tique() }
    }

    private fun num(c: Estilo.Campo): Double? = c.edit.text.toString().trim().replace(',', '.').toDoubleOrNull()
    private fun f(v: Double, casas: Int) = "%.${casas}f".format(v).replace('.', ',')
    private fun arquivo() = PontoGnss.arquivo(pasta, etObra.edit.text.toString())

    // ------------------------------------------------------------------ tela
    fun montar(): ScrollView {
        val ctx = act
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(24)) }

        val cSit = Estilo.cartao(ctx)
        cSit.addView(Estilo.tituloCartao(ctx, "GNSS do scanner"))
        tvSituacao = Estilo.texto(ctx, "", 14f, Estilo.TEXTO_2)
        cSit.addView(tvSituacao)
        btLigar = Estilo.botao(ctx, "Ligar o GNSS (sem levantamento)", Estilo.TipoBotao.CHEIO) { ligarGnss() }
        cSit.addView(btLigar)
        btDesligar = Estilo.botao(ctx, "Desligar o GNSS", Estilo.TipoBotao.CONTORNO) { desligarGnss() }
        cSit.addView(btDesligar)
        cSit.addView(Estilo.texto(ctx, "Liga o receptor do scanner sem iniciar o levantamento (nada é gravado no cartão nem no " +
            "scanner; um reinício volta tudo ao normal). Se você iniciar um levantamento, a posição passa a vir dele. " +
            "O RTK vem do caster da aba RTK.", 12f, Estilo.TEXTO_3).apply { setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), 0) })
        col.addView(cSit)

        val cObra = Estilo.cartao(ctx)
        cObra.addView(Estilo.tituloCartao(ctx, "Obra"))
        etObra = Estilo.campo(ctx, "Nome da obra (um arquivo de pontos por obra)",
            prefs.getString("gnss_obra", null) ?: prefs.getString("projeto", "") ?: "", dica = "ex.: rua_matriz")
        etObra.edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        etObra.edit.setOnFocusChangeListener { _, tem -> if (!tem) { prefs.edit().putString("gnss_obra", etObra.edit.text.toString().trim()).apply(); atualizarLista() } }
        cObra.addView(etObra.bloco)
        tvArquivo = Estilo.texto(ctx, "", 12f, Estilo.TEXTO_3)
        cObra.addView(tvArquivo)
        col.addView(cObra)

        val cSup = Estilo.cartao(ctx)
        cSup.addView(Estilo.tituloCartao(ctx, "Suporte"))
        val linhaSup = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btBraco = Estilo.botao(ctx, PontoGnss.Suporte.BRACO.rotulo, Estilo.TipoBotao.CONTORNO) { escolherSuporte(PontoGnss.Suporte.BRACO) }
        btBastao = Estilo.botao(ctx, PontoGnss.Suporte.BASTAO.rotulo, Estilo.TipoBotao.CONTORNO) { escolherSuporte(PontoGnss.Suporte.BASTAO) }
        for (b in listOf(btBraco, btBastao)) b.textSize = 14f
        linhaSup.addView(btBraco, LinearLayout.LayoutParams(0, ctx.dp(54), 1f).apply { rightMargin = ctx.dp(8) })
        linhaSup.addView(btBastao, LinearLayout.LayoutParams(0, ctx.dp(54), 1f))
        cSup.addView(linhaSup)
        val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        blocoBraco = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ctx.dp(12), 0, 0) }
        etBraco = Estilo.campo(ctx, "Ponta do braço → antena (m)", prefs.getString("gnss_braco", null) ?: "0.253", decimal)
        blocoBraco.addView(etBraco.bloco)
        blocoBraco.addView(Estilo.texto(ctx, "0,253 m vem do .pro do GS-102G (braço da âncora e da antena). Confira num marco conhecido " +
            "antes de usar em serviço.", 12f, Estilo.TEXTO_3))
        cSup.addView(blocoBraco)
        blocoBastao = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ctx.dp(12), 0, 0) }
        etBastao = Estilo.campo(ctx, "Altura do bastão (m)", prefs.getString("gnss_bastao", null) ?: "2.000", decimal)
        etRosca = Estilo.campo(ctx, "Rosca → antena (m)", prefs.getString("gnss_rosca", null) ?: "", decimal, "medir uma vez e guardar")
        etRosca.edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        blocoBastao.addView(etBastao.bloco); blocoBastao.addView(etRosca.bloco)
        blocoBastao.addView(Estilo.texto(ctx, "Rosca → antena: desnível da rosca de baixo do scanner até a antena. Para conferir: meça o " +
            "mesmo marco com o braço e com o bastão; a diferença de cota é o erro dessa medida.", 12f, Estilo.TEXTO_3))
        cSup.addView(blocoBastao)
        col.addView(cSup)

        val cMed = Estilo.cartao(ctx)
        cMed.addView(Estilo.tituloCartao(ctx, "Medir ponto"))
        etNome = Estilo.campo(ctx, "Nome do ponto", "1", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        etDesc = Estilo.campo(ctx, "Descrição / código (PC… = ponto de controle)", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, "ex.: PC1 marco, poste, divisa")
        etTempo = Estilo.campo(ctx, "Tempo de cada leitura (s)", prefs.getInt("gnss_tempo", 10).toString(), InputType.TYPE_CLASS_NUMBER)
        etTempo.edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        for (c in listOf(etNome, etDesc, etTempo)) cMed.addView(c.bloco)
        cbFlutuante = CheckBox(ctx).apply {
            text = "Aceitar RTK flutuante (só para pontos sem precisão)"; setTextColor(Estilo.TEXTO); textSize = 14f
            isChecked = prefs.getBoolean("gnss_flutuante", false)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("gnss_flutuante", v).apply() }
        }
        cMed.addView(cbFlutuante)
        tvMedicao = Estilo.texto(ctx, "", 14f, Estilo.TEXTO).apply { setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), ctx.dp(4)) }
        cMed.addView(tvMedicao)
        btMedir = Estilo.botao(ctx, "Medir leitura 1", Estilo.TipoBotao.SUCESSO) { medir() }
        cMed.addView(btMedir)
        btCancelar = Estilo.botao(ctx, "Cancelar o ponto", Estilo.TipoBotao.CONTORNO) { cancelar() }
        cMed.addView(btCancelar)
        col.addView(cMed)

        val cPts = Estilo.cartao(ctx)
        cPts.addView(Estilo.tituloCartao(ctx, "Pontos da obra"))
        cPts.addView(Estilo.botao(ctx, "Compartilhar o CSV", Estilo.TipoBotao.CONTORNO) { compartilhar() })
        lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ctx.dp(8), 0, 0) }
        cPts.addView(lista)
        col.addView(cPts)

        campos = listOf(etObra, etBraco, etBastao, etRosca, etNome, etDesc, etTempo)
        escolherSuporte(suporte)
        atualizarLista()
        etNome.edit.setText(PontoGnss.proximoNome(PontoGnss.ler(arquivo()).map { it.nome }))
        tique()
        return ScrollView(ctx).apply { addView(col); clipToPadding = false }
    }

    private fun escolherSuporte(s: PontoGnss.Suporte) {
        suporte = s
        prefs.edit().putString("gnss_suporte", s.codigo).apply()
        Estilo.aplicarTipo(act, btBraco, if (s == PontoGnss.Suporte.BRACO) Estilo.TipoBotao.CHEIO else Estilo.TipoBotao.CONTORNO)
        Estilo.aplicarTipo(act, btBastao, if (s == PontoGnss.Suporte.BASTAO) Estilo.TipoBotao.CHEIO else Estilo.TipoBotao.CONTORNO)
        blocoBraco.visibility = if (s == PontoGnss.Suporte.BRACO) View.VISIBLE else View.GONE
        blocoBastao.visibility = if (s == PontoGnss.Suporte.BASTAO) View.VISIBLE else View.GONE
    }

    /** Altura da ponta até a antena pelo suporte escolhido, ou a mensagem do que falta preencher. */
    private fun alturaAntena(): Pair<Double?, String> = when (suporte) {
        PontoGnss.Suporte.BRACO -> num(etBraco)?.let { it to "" } ?: (null to "preencha a medida ponta do braço → antena")
        PontoGnss.Suporte.BASTAO -> {
            val b = num(etBastao); val r = num(etRosca)
            if (b == null) null to "preencha a altura do bastão"
            else if (r == null) null to "preencha a medida rosca → antena (meça uma vez; fica guardada)"
            else (b + r) to ""
        }
    }

    // ------------------------------------------------------------------ medição
    private fun medir() {
        when (etapa) {
            Etapa.PARADO -> {
                val (h, falta) = alturaAntena()
                if (h == null) { toast(falta); return }
                if (System.currentTimeMillis() - ultimoGgaMs > 3000) { toast("Sem posição do GNSS. Toque em Ligar o GNSS primeiro."); return }
                prefs.edit().putString("gnss_braco", etBraco.edit.text.toString().trim()).putString("gnss_bastao", etBastao.edit.text.toString().trim())
                    .putString("gnss_rosca", etRosca.edit.text.toString().trim()).putInt("gnss_tempo", tempo())
                    .putString("gnss_obra", etObra.edit.text.toString().trim()).apply()
                leitura1 = null
                coletor = PontoGnss.Coletor(tempo(), cbFlutuante.isChecked)
                etapa = Etapa.LEITURA1
            }
            Etapa.GIRAR -> {
                coletor = PontoGnss.Coletor(tempo(), cbFlutuante.isChecked, leitura1?.fuso)
                etapa = Etapa.LEITURA2
            }
            else -> return
        }
        esconderTeclado()
        tique()
    }

    private fun tempo() = (etTempo.edit.text.toString().trim().toIntOrNull() ?: 10).coerceIn(3, 600)

    private fun cancelar() {
        coletor = null; leitura1 = null; etapa = Etapa.PARADO; tique()
    }

    /** A cada segundo e a cada GGA: situação do GNSS, andamento da leitura e passagem de etapa. */
    fun tique() {
        if (!::tvSituacao.isInitialized) return
        val agora = System.currentTimeMillis()
        val g = ultimoGga
        val idade = (agora - ultimoGgaMs) / 1000.0
        val iniciado = gnssLigado()
        btLigar.visibility = if (iniciado) View.GONE else View.VISIBLE
        btDesligar.visibility = if (iniciado) View.VISIBLE else View.GONE
        val aux = estadoAuxiliar.let { if (it.isEmpty()) "" else "\n$it" }
        when {
            g == null || idade > 5 -> {
                tvSituacao.text = (if (!iniciado) "Sem posição. Conecte o scanner e toque em Ligar o GNSS."
                    else if (g == null) "Aguardando a primeira posição do receptor…"
                    else "Sem posição nova há %.0f s.".format(idade)) + aux
                tvSituacao.setTextColor(if (iniciado) Estilo.ATENCAO else Estilo.TEXTO_2)
            }
            else -> {
                val corr = g.idadeCorrecao?.let { " · correção há ${f(it, 0)} s" } ?: ""
                tvSituacao.text = "${g.nomeSolucao()} · ${g.satelites} satélites" + (g.hdop?.let { " · HDOP ${f(it, 1)}" } ?: "") + corr + aux
                tvSituacao.setTextColor(when (g.qualidade) { 4 -> Estilo.OK; 5 -> Estilo.ATENCAO; else -> Estilo.ERRO })
            }
        }
        val c = coletor
        if ((etapa == Etapa.LEITURA1 || etapa == Etapa.LEITURA2) && c != null) {
            if (c.completo(agora)) { terminarLeitura(c); return }
            val ref = if (c.ultimaAceitaMs > 0) c.ultimaAceitaMs else c.inicioMs
            if (agora - ref > 20_000) {
                apito(3)
                toast(if (cbFlutuante.isChecked) "Sem RTK há 20 s: leitura cancelada" else "Sem RTK FIXO há 20 s: leitura cancelada")
                coletor = null
                etapa = if (etapa == Etapa.LEITURA2) Etapa.GIRAR else Etapa.PARADO
                pintarMedicao(); return
            }
        }
        pintarMedicao()
    }

    private fun terminarLeitura(c: PontoGnss.Coletor) {
        val l = c.leitura() ?: return
        coletor = null
        apito(1)
        if (etapa == Etapa.LEITURA1) {
            leitura1 = l; etapa = Etapa.GIRAR; pintarMedicao()
        } else {
            etapa = Etapa.PARADO; pintarMedicao()
            val l1 = leitura1 ?: return
            val (h, _) = alturaAntena()
            mostrarResultado(PontoGnss.Resultado(etNome.edit.text.toString().trim().ifEmpty { "1" }, etDesc.edit.text.toString().trim(),
                suporte, h ?: 0.0, l1, l))
        }
    }

    private fun pintarMedicao() {
        val c = coletor
        btCancelar.visibility = if (etapa == Etapa.PARADO) View.GONE else View.VISIBLE
        when (etapa) {
            Etapa.PARADO -> {
                tvMedicao.text = "Ponta no ponto, scanner nivelado e parado: toque em Medir leitura 1."
                btMedir.text = "Medir leitura 1"; btMedir.isEnabled = true; Estilo.aplicarTipo(act, btMedir, Estilo.TipoBotao.SUCESSO)
            }
            Etapa.LEITURA1, Etapa.LEITURA2 -> {
                val k = if (etapa == Etapa.LEITURA1) 1 else 2
                val seg = if (c == null || c.primeiraMs == 0L) 0 else ((System.currentTimeMillis() - c.primeiraMs) / 1000).toInt()
                tvMedicao.text = "Leitura $k: ${c?.aceitas ?: 0} épocas · $seg de ${tempo()} s" +
                    (if ((c?.rejeitadas ?: 0) > 0) " · ${c?.rejeitadas} descartadas (sem fixo)" else "") + "\nNão mexa no scanner."
                btMedir.text = "Lendo… ($seg/${tempo()} s)"; btMedir.isEnabled = false; Estilo.aplicarTipo(act, btMedir, Estilo.TipoBotao.CONTORNO)
            }
            Etapa.GIRAR -> {
                tvMedicao.text = "Leitura 1 ok. Agora GIRE O SCANNER 180° em volta da ponta, sem tirar a ponta do ponto, " +
                    "nivele de novo e toque em Medir leitura 2."
                btMedir.text = "Girei 180°: medir leitura 2"; btMedir.isEnabled = true; Estilo.aplicarTipo(act, btMedir, Estilo.TipoBotao.SUCESSO)
            }
        }
    }

    private fun mostrarResultado(r: PontoGnss.Resultado) {
        val av = r.avisos()
        val msg = buildString {
            append("E ${f(r.e, 3)}\nN ${f(r.n, 3)}\nCota ${f(r.altitude, 3)} (geoide do receptor)\n")
            append("h elipsoidal ${f(r.hElip, 3)}   ·   fuso ${r.l1.fuso}${if (r.l1.sul) "S" else "N"}\n\n")
            append("Oscilação: H ${f(r.sdHz * 100, 1)} cm · V ${f(r.sdV * 100, 1)} cm · ${r.epocas} épocas ${if (r.soFixo) "(fixo)" else "(com flutuante)"}\n")
            append("Afastamento da antena: ${f(r.afastamento * 100, 1)} cm · diferença de altura entre leituras: ${f(r.difVertical * 100, 1)} cm\n")
            append("Suporte: ${r.suporte.rotulo}, ponta → antena ${f(r.alturaAntena, 3)} m")
            if (av.isNotEmpty()) append("\n\nATENÇÃO:\n" + av.joinToString("\n") { "• $it" })
        }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Ponto ${r.nome}" + if (av.isEmpty()) "" else " (confira)")
            .setMessage(msg)
            .setPositiveButton("Salvar") { _, _ -> salvar(r) }
            .setNegativeButton("Descartar", null)
            .setCancelable(false).show()
        if (av.isNotEmpty()) apito(2)
    }

    private fun salvar(r: PontoGnss.Resultado) {
        val arq = arquivo()
        try {
            PontoGnss.salvar(arq, r)
        } catch (e: Exception) { toast("Não consegui gravar: ${e.message}"); return }
        toast("Ponto ${r.nome} salvo")
        etNome.edit.setText(PontoGnss.proximoNome(listOf(r.nome)))
        atualizarLista()
    }

    // ------------------------------------------------------------------ lista e exportação
    private fun atualizarLista() {
        if (!::lista.isInitialized) return
        val arq = arquivo()
        tvArquivo.text = "Arquivo: Android/data/br.scanner.campo/files/logs_slam/${arq.name}\n" +
            "Formato dos coletores (Name, Easting, Northing, Elevation, Feature code…), lido direto pelo Gerador."
        lista.removeAllViews()
        val pts = PontoGnss.ler(arq)
        if (pts.isEmpty()) {
            lista.addView(Estilo.texto(act, "Nenhum ponto ainda nesta obra.", 13f, Estilo.TEXTO_2)); return
        }
        for ((i, p) in pts.withIndex().reversed().take(40)) {
            val t = Estilo.texto(act, "${p.nome}${if (p.descricao.isNotEmpty()) " · " + p.descricao else ""}   ${if (p.estado == "FIXED") "fixo" else "FLUTUANTE"}\n" +
                "E ${f(p.e, 3)}  N ${f(p.n, 3)}  cota ${f(p.elev, 3)}", 13f, if (p.estado == "FIXED") Estilo.TEXTO else Estilo.ATENCAO).apply {
                setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
                background = Estilo.forma(act, Estilo.SUPERFICIE_2, 10)
                setOnLongClickListener {
                    AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert).setTitle("Apagar o ponto ${p.nome}?")
                        .setMessage("Tira o ponto do arquivo ${arq.name} no celular. Não dá para desfazer.")
                        .setPositiveButton("Apagar") { _, _ -> PontoGnss.remover(arq, i); atualizarLista() }
                        .setNegativeButton("Cancelar", null).show()
                    true
                }
            }
            lista.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(6) })
        }
        lista.addView(Estilo.texto(act, "${pts.size} ponto(s). Toque longo para apagar um ponto.", 12f, Estilo.TEXTO_3).apply {
            setPadding(act.dp(2), act.dp(8), 0, 0); gravity = Gravity.START })
    }

    /** Manda o CSV como texto (WhatsApp, e-mail, Drive…): sem FileProvider, o app não tem bibliotecas extras. */
    private fun compartilhar() {
        val arq = arquivo()
        if (!arq.exists()) { toast("Nenhum ponto ainda nesta obra"); return }
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, arq.name).putExtra(Intent.EXTRA_TEXT, arq.readText())
        act.startActivity(Intent.createChooser(i, "Compartilhar ${arq.name}"))
    }

    private fun esconderTeclado() {
        val v = act.currentFocus ?: return
        (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private fun toast(t: String) = android.widget.Toast.makeText(act, t, android.widget.Toast.LENGTH_SHORT).show()
}
