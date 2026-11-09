package br.scanner.campo

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nuvem de pontos em 3D (OpenGL ES 2.0, sem biblioteca externa), cor pela altura.
 *  - 1 dedo: girar em volta do centro;  2 dedos: mover (arrastar) e zoom (pinça);  toque duplo: voltar à vista inicial.
 * Vista em órbita: o olho gira em volta de [alvo] a uma distância [dist], com Z para cima.
 */
class Visualizador3D(ctx: Context) : GLSurfaceView(ctx) {
    private val r = Render()

    // câmera (lida na linha do OpenGL; mexida na linha da tela: sincronizado em `this`)
    private var azimute = -60f          // graus em volta de Z
    private var elevacao = 35f          // graus acima do horizonte
    private var dist = 30f
    private val alvo = floatArrayOf(0f, 0f, 0f)
    private var raio = 15f              // tamanho da nuvem (para a vista inicial e o zoom)
    private val centro = floatArrayOf(0f, 0f, 0f)
    var tamanhoPonto = 3f
        set(v) { field = v; requestRender() }
    var aoMudar: (() -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(r)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Troca os pontos (x, y, z intercalados). [reenquadrar] = volta à vista inicial em volta da nuvem nova. */
    fun definirPontos(xyz: FloatArray, reenquadrar: Boolean) {
        val n = xyz.size / 3
        if (n > 0) {
            // limites robustos (1% a 99%) para centrar e para a escala de cor, sem ser enganado por pontos soltos
            val zs = FloatArray(n) { xyz[3 * it + 2] }; zs.sort()
            val xs = FloatArray(n) { xyz[3 * it] }; xs.sort()
            val ys = FloatArray(n) { xyz[3 * it + 1] }; ys.sort()
            fun q(a: FloatArray, f: Float) = a[min(a.size - 1, max(0, (f * (a.size - 1)).toInt()))]
            synchronized(this) {
                centro[0] = (q(xs, 0.01f) + q(xs, 0.99f)) / 2; centro[1] = (q(ys, 0.01f) + q(ys, 0.99f)) / 2
                centro[2] = (q(zs, 0.01f) + q(zs, 0.99f)) / 2
                val dx = q(xs, 0.99f) - q(xs, 0.01f); val dy = q(ys, 0.99f) - q(ys, 0.01f); val dz = q(zs, 0.99f) - q(zs, 0.01f)
                raio = max(2f, sqrt(dx * dx + dy * dy + dz * dz) / 2)
                r.zMin = q(zs, 0.02f); r.zMax = max(q(zs, 0.98f), r.zMin + 0.5f)
                if (reenquadrar) inicial()
            }
        }
        queueEvent { r.carregar(xyz) }
        requestRender()
    }

    private fun inicial() {
        alvo[0] = centro[0]; alvo[1] = centro[1]; alvo[2] = centro[2]
        azimute = -60f; elevacao = 35f; dist = raio * 2.2f
    }

    fun vistaInicial() { synchronized(this) { inicial() }; requestRender() }

    fun vistaTopo() { synchronized(this) { inicial(); elevacao = 89f; azimute = -90f }; requestRender() }

    // ------------------------------------------------------------------------------------------------ gestos
    private var ultX = 0f; private var ultY = 0f; private var ultN = 0
    private val pinca = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            synchronized(this@Visualizador3D) { dist = (dist / d.scaleFactor).coerceIn(0.5f, raio * 20) }
            requestRender(); return true
        }
    })
    private val toques = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { vistaInicial(); return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        pinca.onTouchEvent(e); toques.onTouchEvent(e)
        var sx = 0f; var sy = 0f
        for (i in 0 until e.pointerCount) { sx += e.getX(i); sy += e.getY(i) }
        val n = e.pointerCount; sx /= n; sy /= n
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { ultX = sx; ultY = sy; ultN = n }
            MotionEvent.ACTION_MOVE -> {
                if (n != ultN) { ultX = sx; ultY = sy; ultN = n; return true }
                val dx = sx - ultX; val dy = sy - ultY
                ultX = sx; ultY = sy
                synchronized(this) {
                    if (n == 1) {                                         // girar
                        azimute -= dx * 0.3f
                        elevacao = (elevacao + dy * 0.3f).coerceIn(-89f, 89f)
                    } else {                                              // mover o alvo no plano da tela
                        val k = dist * 1.6f / max(1, height)
                        val az = Math.toRadians(azimute.toDouble()); val el = Math.toRadians(elevacao.toDouble())
                        // direita da tela e "para cima" da tela, no mundo
                        val dirX = -sin(az).toFloat(); val dirY = cos(az).toFloat()
                        val upX = (-sin(el) * cos(az)).toFloat(); val upY = (-sin(el) * sin(az)).toFloat(); val upZ = cos(el).toFloat()
                        alvo[0] += (-dx * dirX + dy * upX) * k
                        alvo[1] += (-dx * dirY + dy * upY) * k
                        alvo[2] += (dy * upZ) * k
                    }
                }
                requestRender(); aoMudar?.invoke()
            }
        }
        return true
    }

    // ------------------------------------------------------------------------------------------------ OpenGL
    private inner class Render : Renderer {
        var zMin = 0f; var zMax = 1f
        private var prog = 0; private var vbo = 0; private var nPts = 0
        private var pendente: FloatArray? = null
        private val proj = FloatArray(16); private val vista = FloatArray(16); private val mvp = FloatArray(16)

        fun carregar(xyz: FloatArray) { pendente = xyz }

        override fun onSurfaceCreated(gl: GL10?, c: EGLConfig?) {
            GLES20.glClearColor(0.055f, 0.063f, 0.078f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            prog = programa(VS, FS)
            val b = IntArray(1); GLES20.glGenBuffers(1, b, 0); vbo = b[0]
            nPts = 0
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            Matrix.perspectiveM(proj, 0, 55f, w.toFloat() / max(1, h), 0.1f, 5000f)
        }

        override fun onDrawFrame(gl: GL10?) {
            pendente?.let { xyz ->
                pendente = null
                val fb = ByteBuffer.allocateDirect(xyz.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                fb.put(xyz).position(0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, xyz.size * 4, fb, GLES20.GL_STATIC_DRAW)
                nPts = xyz.size / 3
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (nPts == 0) return
            val olho = FloatArray(3); val a = FloatArray(3)
            synchronized(this@Visualizador3D) {
                val az = Math.toRadians(azimute.toDouble()); val el = Math.toRadians(elevacao.toDouble())
                olho[0] = alvo[0] + (dist * cos(el) * cos(az)).toFloat()
                olho[1] = alvo[1] + (dist * cos(el) * sin(az)).toFloat()
                olho[2] = alvo[2] + (dist * sin(el)).toFloat()
                a[0] = alvo[0]; a[1] = alvo[1]; a[2] = alvo[2]
            }
            Matrix.setLookAtM(vista, 0, olho[0], olho[1], olho[2], a[0], a[1], a[2], 0f, 0f, 1f)
            Matrix.multiplyMM(mvp, 0, proj, 0, vista, 0)
            GLES20.glUseProgram(prog)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uZ"), zMin, zMax)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTam"), tamanhoPonto)
            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, 0)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, nPts)
            GLES20.glDisableVertexAttribArray(aPos)
        }

        private fun programa(vs: String, fs: String): Int {
            fun sh(t: Int, s: String) = GLES20.glCreateShader(t).also { GLES20.glShaderSource(it, s); GLES20.glCompileShader(it) }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }
    }

    companion object {
        private const val VS = """
            uniform mat4 uMVP; uniform vec2 uZ; uniform float uTam;
            attribute vec3 aPos; varying vec3 vCor;
            void main() {
                gl_Position = uMVP * vec4(aPos, 1.0);
                gl_PointSize = uTam;
                float t = clamp((aPos.z - uZ.x) / (uZ.y - uZ.x), 0.0, 1.0);
                // azul -> ciano -> verde -> amarelo -> vermelho (altura)
                vec3 c1 = vec3(0.15, 0.35, 0.95); vec3 c2 = vec3(0.10, 0.80, 0.85); vec3 c3 = vec3(0.30, 0.85, 0.30);
                vec3 c4 = vec3(0.98, 0.85, 0.20); vec3 c5 = vec3(0.95, 0.30, 0.20);
                if (t < 0.25) vCor = mix(c1, c2, t / 0.25);
                else if (t < 0.5) vCor = mix(c2, c3, (t - 0.25) / 0.25);
                else if (t < 0.75) vCor = mix(c3, c4, (t - 0.5) / 0.25);
                else vCor = mix(c4, c5, (t - 0.75) / 0.25);
            }"""
        private const val FS = """
            precision mediump float; varying vec3 vCor;
            void main() { gl_FragColor = vec4(vCor, 1.0); }"""
    }
}
