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

    // rumo pelo celular preso no scanner + trajetória do GNSS (leitura única)
    private val rumo = RumoCelular(act)
    private val trajetoria = Trajetoria()
    @Volatile private var fusoTrajetoria: Int? = null
    @Volatile private var rumoPosicaoMs = 0L
    private var metodoUnico = prefs.getBoolean("gnss_unico", false)
    private var medindoUnico = false
    private lateinit var tvRumo: TextView
    private lateinit var btUnico: Button
    private lateinit var btDuplo: Button

    private fun chaveCal() = "gnss_cal_" + suporte.codigo
    private fun calibracao(): Excentricidade.Calibracao? = Excentricidade.Calibracao.de(prefs.getString(chaveCal(), null))

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
        val agora = System.currentTimeMillis()
        ultimoGga = g; ultimoGgaMs = agora
        val lat = g.lat; val lon = g.lon
        if (lat != null && lon != null && agora - rumoPosicaoMs > 60_000) { rumo.posicao(lat, lon, g.altitude ?: 0.0); rumoPosicaoMs = agora }
        val r = rumo.quadricula(agora)
        coletor?.adicionar(g, agora, r)
        if (g.qualidade == 4 && lat != null && lon != null) {
            val u = Geodesia.utm(lat, lon, fusoTrajetoria)
            fusoTrajetoria = u.fuso
            trajetoria.adicionar(u.e, u.n, agora, r)
        }
        act.runOnUiThread { tique() }
    }

    fun fechar() = rumo.desligar()

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

        val cRumo = Estilo.cartao(ctx)
        cRumo.addView(Estilo.tituloCartao(ctx, "Método do ponto"))
        val linhaMet = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btUnico = Estilo.botao(ctx, "1 leitura (rumo)", Estilo.TipoBotao.CONTORNO) { escolherMetodo(true) }
        btDuplo = Estilo.botao(ctx, "2 leituras 180°", Estilo.TipoBotao.CONTORNO) { escolherMetodo(false) }
        for (b in listOf(btUnico, btDuplo)) b.textSize = 14f
        linhaMet.addView(btUnico, LinearLayout.LayoutParams(0, ctx.dp(54), 1f).apply { rightMargin = ctx.dp(8) })
        linhaMet.addView(btDuplo, LinearLayout.LayoutParams(0, ctx.dp(54), 1f))
        cRumo.addView(linhaMet)
        tvRumo = Estilo.texto(ctx, "", 13f, Estilo.TEXTO_2).apply { setPadding(ctx.dp(2), ctx.dp(10), ctx.dp(2), 0) }
        cRumo.addView(tvRumo)
        cRumo.addView(Estilo.texto(ctx, "1 leitura: o celular preso no scanner (apontando para o mesmo lado) dá o rumo, e o app " +
            "desconta o afastamento da antena. Calibre com uma leitura dupla (a cada dia ou se mexer no celular): o app aprende " +
            "o afastamento sozinho. Andando com RTK fixo entre os pontos, a trajetória confere a bússola.", 12f, Estilo.TEXTO_3)
            .apply { setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), 0) })
        col.addView(cRumo)
        rumo.ligar()

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
        escolherMetodo(metodoUnico)
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

    private fun escolherMetodo(unico: Boolean) {
        if (etapa != Etapa.PARADO) { toast("Termine ou cancele o ponto antes"); return }
        metodoUnico = unico
        prefs.edit().putBoolean("gnss_unico", unico).apply()
        Estilo.aplicarTipo(act, btUnico, if (unico) Estilo.TipoBotao.CHEIO else Estilo.TipoBotao.CONTORNO)
        Estilo.aplicarTipo(act, btDuplo, if (!unico) Estilo.TipoBotao.CHEIO else Estilo.TipoBotao.CONTORNO)
        if (::tvMedicao.isInitialized) pintarMedicao()
    }

    private fun textoRumo(): String {
        val r = rumo.quadricula()
        val q = when (rumo.qualidade) { 3 -> "boa"; 2 -> "média"; 1 -> "fraca: faça um 8 com o celular"; else -> "não confiável: faça um 8 com o celular" }
        val linhaRumo = if (!rumo.disponivel) "Este celular não tem os sensores de rumo."
            else if (r.isNaN()) "Rumo: aguardando os sensores do celular…"
            else "Rumo do scanner: ${f(r, 1)}° (quadrícula) · celular pelo ${rumo.eixo} · bússola $q"
        val c = calibracao()
        val linhaCal = if (c == null) "Sem calibração para o ${suporte.rotulo.lowercase()}: faça um ponto com 2 leituras 180°."
            else "Calibração: antena a ${f(c.distancia * 100, 1)} cm, ${f(c.angulo, 0)}° do celular · coerência ${f(c.coerencia, 0)}° · " +
                java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(c.quandoMs)) +
                (if (c.eixo != rumo.eixo && rumo.eixo.isNotEmpty()) "\nATENÇÃO: calibrado com o celular pelo ${c.eixo}; agora está pelo ${rumo.eixo}." else "")
        val t = trajetoria.estimativa()
        val linhaTraj = if (t == null) "Trajetória: ande com RTK fixo para conferir a bússola."
            else "Trajetória × celular: ${f(t.first, 0)}° (±${f(t.second, 0)}°, ${t.third} trechos)" +
                (c?.trajetoria?.let { " · na calibração ${f(it, 0)}°" } ?: "")
        return "$linhaRumo\n$linhaCal\n$linhaTraj"
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
                if (metodoUnico) {
                    val c = calibracao()
                    if (c == null) { toast("Sem calibração: faça um ponto com 2 leituras 180° primeiro"); return }
                    if (rumo.quadricula().isNaN()) { toast("Sem rumo do celular"); return }
                    if (c.eixo != rumo.eixo) { toast("O celular está em outra posição que na calibração: calibre de novo (2 leituras)"); return }
                }
                medindoUnico = metodoUnico
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
                tvSituacao.text = (if (!iniciado) "Sem posição. Toque em Ligar o GNSS (conecta ao scanner sozinho, se preciso)."
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
        tvRumo.text = textoRumo()
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
        val nome = etNome.edit.text.toString().trim().ifEmpty { "1" }
        val desc = etDesc.edit.text.toString().trim()
        val (h, _) = alturaAntena()
        if (etapa == Etapa.LEITURA1 && medindoUnico) {
            etapa = Etapa.PARADO; pintarMedicao()
            leituraUnica(nome, desc, h ?: 0.0, l)
        } else if (etapa == Etapa.LEITURA1) {
            leitura1 = l; etapa = Etapa.GIRAR; pintarMedicao()
        } else {
            etapa = Etapa.PARADO; pintarMedicao()
            val l1 = leitura1 ?: return
            val r = PontoGnss.Resultado.duasLeituras(nome, desc, suporte, h ?: 0.0, l1, l)
            mostrarResultado(r, Excentricidade.calibrar(l1, l, rumo.eixo, trajetoria.estimativa()?.first))
        }
    }

    /**
     * Ponto com uma leitura: rumo médio do celular na leitura, corrigido pela trajetória quando ela mostra que a bússola
     * mudou desde a calibração (diferença trajetória − celular estável, ≥ 5 trechos).
     */
    private fun leituraUnica(nome: String, desc: String, h: Double, l: PontoGnss.Leitura) {
        val c = calibracao() ?: return
        var rumoUsado = l.rumo ?: run { toast("Faltou o rumo do celular durante a leitura: meça de novo"); apito(3); return }
        val extras = ArrayList<String>()
        if (l.sdRumo > 5) extras.add("o rumo oscilou ${f(l.sdRumo, 0)}° durante a leitura (scanner girando ou bússola instável)")
        if (c.coerencia > 15) extras.add("a calibração tem coerência ruim (${f(c.coerencia, 0)}°): refaça com 2 leituras")
        val t = trajetoria.estimativa()
        val cTraj = c.trajetoria
        if (t != null && cTraj != null && t.second < 10) {
            val delta = RumoCelular.diferenca(t.first, cTraj)
            when {
                kotlin.math.abs(delta) > 25 -> extras.add("a bússola mudou ${f(delta, 0)}° desde a calibração (pela trajetória): calibre de novo com 2 leituras")
                kotlin.math.abs(delta) > 3 -> { rumoUsado = RumoCelular.normalizar(rumoUsado + delta); extras.add("rumo corrigido pela trajetória em ${f(delta, 0)}° (informativo)") }
            }
        }
        // erro de rumo de ~10° com o afastamento calibrado: só para mostrar a ordem de grandeza
        val erroCm = c.distancia * Math.sin(Math.toRadians(10.0)) * 100
        if (erroCm > 2) extras.add("afastamento grande (${f(c.distancia * 100, 0)} cm): cada 10° de erro no rumo = ${f(erroCm, 1)} cm")
        mostrarResultado(PontoGnss.Resultado.leituraUnica(nome, desc, suporte, h, l, c, rumoUsado, extras), null)
    }

    private fun pintarMedicao() {
        val c = coletor
        btCancelar.visibility = if (etapa == Etapa.PARADO) View.GONE else View.VISIBLE
        when (etapa) {
            Etapa.PARADO -> {
                tvMedicao.text = if (metodoUnico) "Ponta no ponto, scanner nivelado e parado (para qualquer lado): toque em Medir."
                    else "Ponta no ponto, scanner nivelado e parado: toque em Medir leitura 1."
                btMedir.text = if (metodoUnico) "Medir (1 leitura)" else "Medir leitura 1"
                btMedir.isEnabled = true; Estilo.aplicarTipo(act, btMedir, Estilo.TipoBotao.SUCESSO)
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

    /** [cal]: calibração do rumo tirada desta leitura dupla (gravada junto com o ponto, se a coerência for boa). */
    private fun mostrarResultado(r: PontoGnss.Resultado, cal: Excentricidade.Calibracao?) {
        val av = r.avisos()
        val calOk = cal != null && cal.coerencia <= 20
        val msg = buildString {
            append("E ${f(r.e, 3)}\nN ${f(r.n, 3)}\nCota ${f(r.altitude, 3)} (geoide do receptor)\n")
            append("h elipsoidal ${f(r.hElip, 3)}   ·   fuso ${r.l1.fuso}${if (r.l1.sul) "S" else "N"}\n\n")
            append("Método: ${r.metodo}" + (r.rumo?.let { " · rumo ${f(it, 1)}°" } ?: "") + "\n")
            append("Oscilação: H ${f(r.sdHz * 100, 1)} cm · V ${f(r.sdV * 100, 1)} cm · ${r.epocas} épocas ${if (r.soFixo) "(fixo)" else "(com flutuante)"}\n")
            if (r.l2 != null) append("Afastamento da antena: ${f(r.afastamento * 100, 1)} cm · diferença de altura entre leituras: ${f(r.difVertical * 100, 1)} cm\n")
            else append("Afastamento descontado: ${f(r.afastamento * 100, 1)} cm\n")
            append("Suporte: ${r.suporte.rotulo}, ponta → antena ${f(r.alturaAntena, 3)} m")
            if (cal != null) append("\n\nCalibração do rumo: antena a ${f(cal.distancia * 100, 1)} cm, ${f(cal.angulo, 0)}° do celular, " +
                "coerência ${f(cal.coerencia, 0)}° " + if (calOk) "(será gravada ao salvar)" else "(RUIM: não será usada)")
            else if (r.l2 != null) append("\n\nSem rumo do celular nesta leitura: a calibração não foi atualizada.")
            if (av.isNotEmpty()) append("\n\nATENÇÃO:\n" + av.joinToString("\n") { "• $it" })
        }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Ponto ${r.nome}" + if (av.isEmpty()) "" else " (confira)")
            .setMessage(msg)
            .setPositiveButton("Salvar") { _, _ ->
                salvar(r)
                if (calOk && cal != null) prefs.edit().putString(chaveCal(), cal.texto()).apply()
            }
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
