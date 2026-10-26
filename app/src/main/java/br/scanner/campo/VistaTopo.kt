package br.scanner.campo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/**
 * Vista de cima simples: o caminho percorrido (pose de cada quadro) e os pontos do último quadro em volta.
 * Não é a nuvem completa do FireEye: serve para ver se o traçado faz sentido (um salto aparece na hora).
 */
class VistaTopo(ctx: Context) : View(ctx) {
    private val caminho = ArrayList<FloatArray>()
    @Volatile private var pontos = FloatArray(0)
    private val pCaminho = Paint().apply { color = Color.rgb(0, 200, 255); strokeWidth = 4f; isAntiAlias = true }
    private val pPontos = Paint().apply { color = Color.argb(160, 220, 220, 220); strokeWidth = 2f }
    private val pAtual = Paint().apply { color = Color.rgb(255, 80, 80); isAntiAlias = true }
    private val pTexto = Paint().apply { color = Color.LTGRAY; textSize = 28f; isAntiAlias = true }

    fun limpar() { synchronized(caminho) { caminho.clear() }; pontos = FloatArray(0); postInvalidate() }

    fun adicionar(q: Protocolo.QuadroNuvem) {
        synchronized(caminho) { caminho.add(floatArrayOf(q.pose[0], q.pose[1])) }
        pontos = q.pontos
        postInvalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(12, 14, 18))
        val cam = synchronized(caminho) { ArrayList(caminho) }
        if (cam.isEmpty()) { c.drawText("o caminho aparece aqui durante o levantamento", 20f, height / 2f, pTexto); return }
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (p in cam) { x0 = minOf(x0, p[0]); x1 = max(x1, p[0]); y0 = minOf(y0, p[1]); y1 = max(y1, p[1]) }
        val atual = cam.last()
        x0 = minOf(x0, atual[0] - 15); x1 = max(x1, atual[0] + 15); y0 = minOf(y0, atual[1] - 15); y1 = max(y1, atual[1] + 15)
        val esc = minOf(width / (x1 - x0), height / (y1 - y0)) * 0.9f
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        fun u(x: Float) = width / 2f + (x - cx) * esc
        fun v(y: Float) = height / 2f - (y - cy) * esc
        val pts = pontos
        var i = 0
        while (i + 2 < pts.size) { c.drawPoint(u(pts[i]), v(pts[i + 1]), pPontos); i += 3 }
        for (k in 1 until cam.size) c.drawLine(u(cam[k - 1][0]), v(cam[k - 1][1]), u(cam[k][0]), v(cam[k][1]), pCaminho)
        c.drawCircle(u(atual[0]), v(atual[1]), 9f, pAtual)
        c.drawText("%.0f m".format(10f), 16f, height - 16f, pTexto)
        c.drawLine(80f, height - 26f, 80f + 10 * esc, height - 26f, pTexto)
    }
}
