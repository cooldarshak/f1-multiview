package app.f1multiview.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.view.Surface
import app.f1multiview.core.playback.TiledMultiviewSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * GPU compositor for a genuine single-source tiled/mosaic stream.
 *
 * one Media3 decoder -> one SurfaceTexture -> one GL compositor -> N logical tiles.
 *
 * Protected streams use the same compositor with a protected EGL context/window and
 * protected external texture. No protected pixels are copied to CPU-visible memory.
 */
class OpenTiledCompositorView(
    context: Context,
    private val protectedOutput: Boolean = false
) : GLSurfaceView(context) {
    interface Listener {
        fun onOutputSurfaceReady(surface: Surface)
        fun onOutputSurfaceReleased()
    }

    private val renderer = Renderer()
    private var listener: Listener? = null

    init {
        setEGLContextClientVersion(2)
        if (protectedOutput) {
            installProtectedEgl()
        }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    private fun installProtectedEgl() {
        setEGLConfigChooser(object : EGLConfigChooser {
            override fun chooseConfig(
                egl: javax.microedition.khronos.egl.EGL10,
                display: javax.microedition.khronos.egl.EGLDisplay
            ): javax.microedition.khronos.egl.EGLConfig {
                val attributes = intArrayOf(
                    javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4,
                    0x32C0, 1,
                    javax.microedition.khronos.egl.EGL10.EGL_NONE
                )
                val count = IntArray(1)
                check(egl.eglChooseConfig(display, attributes, null, 0, count) && count[0] > 0) {
                    "Protected EGL config is not supported"
                }
                val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(count[0])
                check(egl.eglChooseConfig(display, attributes, configs, configs.size, count)) {
                    "Unable to choose protected EGL config"
                }
                return requireNotNull(configs[0])
            }
        })
        setEGLContextFactory(object : EGLContextFactory {
            override fun createContext(
                egl: javax.microedition.khronos.egl.EGL10,
                display: javax.microedition.khronos.egl.EGLDisplay,
                config: javax.microedition.khronos.egl.EGLConfig
            ): javax.microedition.khronos.egl.EGLContext {
                val attributes = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    0x32C0, EGL14.EGL_TRUE,
                    EGL14.EGL_NONE
                )
                return egl.eglCreateContext(
                    display, config, javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT, attributes
                )
            }

            override fun destroyContext(
                egl: javax.microedition.khronos.egl.EGL10,
                display: javax.microedition.khronos.egl.EGLDisplay,
                context: javax.microedition.khronos.egl.EGLContext
            ) {
                egl.eglDestroyContext(display, context)
            }
        })
        setEGLWindowSurfaceFactory(object : EGLWindowSurfaceFactory {
            override fun createWindowSurface(
                egl: javax.microedition.khronos.egl.EGL10,
                display: javax.microedition.khronos.egl.EGLDisplay,
                config: javax.microedition.khronos.egl.EGLConfig,
                nativeWindow: Any
            ): javax.microedition.khronos.egl.EGLSurface {
                val attributes = intArrayOf(
                    0x32C0, EGL14.EGL_TRUE,
                    javax.microedition.khronos.egl.EGL10.EGL_NONE
                )
                return egl.eglCreateWindowSurface(display, config, nativeWindow, attributes)
            }

            override fun destroySurface(
                egl: javax.microedition.khronos.egl.EGL10,
                display: javax.microedition.khronos.egl.EGLDisplay,
                surface: javax.microedition.khronos.egl.EGLSurface
            ) {
                egl.eglDestroySurface(display, surface)
            }
        })
    }

    fun setListener(value: Listener?) {
        listener = value
        if (value != null) {
            queueEvent { renderer.notifyExistingOutput(value) }
        }
    }

    fun setSession(value: TiledMultiviewSession) {
        renderer.setSession(value)
        requestRender()
    }

    fun setSelectedFeedIds(value: List<String>) {
        renderer.setSelectedFeedIds(value)
        requestRender()
    }

    fun setSourceVideoSize(width: Int, height: Int) {
        renderer.setSourceVideoSize(width, height)
        requestRender()
    }

    fun releaseOutput() {
        queueEvent { renderer.releaseOutput() }
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var outputSurface: Surface? = null
        private var session: TiledMultiviewSession? = null
        private var selected = emptyList<String>()
        private val frameAvailable = AtomicBoolean(false)

        private lateinit var program: ShaderProgram
        private var vertexBuffer: FloatBuffer? = null
        private var texBuffer: FloatBuffer? = null
        private var width = 0
        private var height = 0
        private var sourceVideoWidth = 0
        private var sourceVideoHeight = 0
        private var decoderPlan: OpenTiledDecoderPlan? = null

        private val vertexShader = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = ShaderProgram(vertexShader, fragmentShader)

            val texture = IntArray(1)
            GLES20.glGenTextures(1, texture, 0)
            textureId = texture[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            surfaceTexture = SurfaceTexture(textureId).also { st ->
                st.setOnFrameAvailableListener {
                    frameAvailable.set(true)
                    requestRender()
                }
            }
            outputSurface = Surface(surfaceTexture)
            listener?.onOutputSurfaceReady(outputSurface!!)
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
            width = w
            height = h
            GLES20.glViewport(0, 0, w, h)
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            if (frameAvailable.compareAndSet(true, false)) {
                surfaceTexture?.updateTexImage()
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val current = session ?: return
            val feeds = selected.ifEmpty {
                current.feeds.map { feed ->
                    feed.uuid ?: feed.channelId?.toString() ?: "feed-${feed.index}"
                }
            }
            if (feeds.isEmpty() || width <= 0 || height <= 0) return

            val outputColumns = max(1, kotlin.math.ceil(kotlin.math.sqrt(feeds.size.toDouble())).toInt())
            val outputRows = max(1, kotlin.math.ceil(feeds.size.toDouble() / outputColumns).toInt())
            val plan = decoderPlan ?: OpenTiledDecoderPlan.from(current, sourceVideoWidth, sourceVideoHeight)
                ?: return
            decoderPlan = plan

            feeds.forEachIndexed { outputIndex, feedId ->
                val binding = plan.binding(feedId) ?: return@forEachIndexed
                val left = outputIndex % outputColumns
                val top = outputIndex / outputColumns
                val viewportWidth = width / outputColumns
                val viewportHeight = height / outputRows

                GLES20.glViewport(
                    left * viewportWidth,
                    height - (top + 1) * viewportHeight,
                    viewportWidth,
                    viewportHeight
                )

                val u0 = binding.sourceRect.left
                val u1 = binding.sourceRect.right
                val v0 = 1f - binding.sourceRect.bottom
                val v1 = 1f - binding.sourceRect.top

                setQuad(u0, v0, u1, v1)
                drawQuad()
            }
        }

        private fun setQuad(u0: Float, v0: Float, u1: Float, v1: Float) {
            vertexBuffer = floatBuffer(
                floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            )
            texBuffer = floatBuffer(
                floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1)
            )
        }

        private fun drawQuad() {
            val position = program.position
            val texCoord = program.texCoord
            GLES20.glUseProgram(program.id)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(texCoord)

            val vertices = vertexBuffer ?: return
            vertices.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)

            val textures = texBuffer ?: return
            textures.position(0)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, textures)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(program.texture, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisableVertexAttribArray(texCoord)
        }

        fun notifyExistingOutput(value: Listener) {
            outputSurface?.let(value::onOutputSurfaceReady)
        }

        fun setSession(value: TiledMultiviewSession) {
            session = value
            if (selected.isEmpty()) {
                selected = value.feeds.map { feed ->
                    feed.uuid ?: feed.channelId?.toString() ?: "feed-${feed.index}"
                }
            }
        }

        fun setSelectedFeedIds(value: List<String>) {
            selected = value.distinct()
        }

        fun setSourceVideoSize(width: Int, height: Int) {
            sourceVideoWidth = width
            sourceVideoHeight = height
        }

        fun releaseOutput() {
            surfaceTexture?.setOnFrameAvailableListener(null)
            outputSurface?.release()
            outputSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
            listener?.onOutputSurfaceReleased()
        }

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }

    private class ShaderProgram(vertexSource: String, fragmentSource: String) {
        val id: Int
        val position: Int
        val texCoord: Int
        val texture: Int

        init {
            val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, vertex)
            GLES20.glAttachShader(id, fragment)
            GLES20.glLinkProgram(id)

            val status = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Open tiled compositor shader link failed" }

            position = GLES20.glGetAttribLocation(id, "aPosition")
            texCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
            texture = GLES20.glGetUniformLocation(id, "uTexture")
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) {
                "Open tiled compositor shader compile failed: " + GLES20.glGetShaderInfoLog(shader)
            }
            return shader
        }
    }
}
