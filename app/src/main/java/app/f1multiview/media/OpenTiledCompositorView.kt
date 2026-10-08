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
    private var feedTapListener: ((String) -> Unit)? = null
    @Volatile private var hitTestSlots = emptyList<OpenTiledMultiviewEngine.OutputSlot>()

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

    companion object {
        /**
         * Checks the public EGL protected-content capability before attempting to
         * create a protected compositor. This prevents a device without the
         * extension from failing later during GLSurfaceView initialization.
         */
        fun supportsProtectedOutput(): Boolean {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
            return try {
                val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
                extensions.split(' ').any { it == "EGL_EXT_protected_content" }
            } finally {
                EGL14.eglTerminate(display)
            }
        }
    }

    fun setListener(value: Listener?) {
        listener = value
        if (value != null) {
            queueEvent { renderer.notifyExistingOutput(value) }
        }
    }

    fun setFeedTapListener(value: ((String) -> Unit)?) {
        feedTapListener = value
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            val normalizedX = event.x / width.coerceAtLeast(1)
            val normalizedY = event.y / height.coerceAtLeast(1)
            val feedId = hitTestSlots
                .sortedByDescending { it.zIndex }
                .firstOrNull {
                    normalizedX >= it.x &&
                        normalizedX <= it.x + it.width &&
                        normalizedY >= it.y &&
                        normalizedY <= it.y + it.height
                }
                ?.feedId
            if (feedId != null) {
                feedTapListener?.invoke(feedId)
                performClick()
            }
        }
        return true
    }

    fun setSession(value: TiledMultiviewSession) {
        queueEvent { renderer.setSession(value) }
        requestRender()
    }

    fun setSelectedFeedIds(value: List<String>) {
        queueEvent { renderer.setSelectedFeedIds(value) }
        requestRender()
    }

    fun setOutputSlots(value: List<OpenTiledMultiviewEngine.OutputSlot>) {
        val normalized = value.distinctBy { it.feedId }.sortedBy { it.zIndex }
        hitTestSlots = normalized
        queueEvent { renderer.setOutputSlots(normalized) }
        requestRender()
    }

    fun setSourceVideoSize(width: Int, height: Int) {
        queueEvent { renderer.setSourceVideoSize(width, height) }
        requestRender()
    }

    fun releaseOutput() {
        val releaseListener = listener
        queueEvent { renderer.releaseOutput(releaseListener) }
    }

    override fun onDetachedFromWindow() {
        releaseOutput()
        super.onDetachedFromWindow()
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
        private var outputSlots = emptyList<OpenTiledMultiviewEngine.OutputSlot>()
        private var renderSlots = emptyList<OpenTiledMultiviewEngine.OutputSlot>()

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
            // EGL/GL recreation invalidates the previous decoder target. Tear down the
            // old physical graph before handing the decoder a new Surface.
            if (outputSurface != null || surfaceTexture != null) {
                listener?.onOutputSurfaceReleased()
                runCatching { outputSurface?.release() }
                runCatching { surfaceTexture?.release() }
                outputSurface = null
                surfaceTexture = null
            }
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = ShaderProgram(vertexShader, fragmentShader)
            vertexBuffer = floatBuffer(
                floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            )
            texBuffer = floatBuffer(FloatArray(8))

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

            val plan = decoderPlan ?: OpenTiledDecoderPlan.from(current, sourceVideoWidth, sourceVideoHeight)
                ?: return
            decoderPlan = plan
            val slots = renderSlots.ifEmpty {
                // Keep the fallback allocation-free during steady-state rendering.
                val columns = when {
                    feeds.size <= 1 -> 1
                    feeds.size <= 4 -> 2
                    else -> 3
                }
                val rows = (feeds.size + columns - 1) / columns
                feeds.mapIndexed { index, feedId ->
                    OpenTiledMultiviewEngine.OutputSlot(
                        feedId = feedId,
                        x = (index % columns).toFloat() / columns,
                        y = (index / columns).toFloat() / rows,
                        width = 1f / columns,
                        height = 1f / rows,
                        zIndex = index
                    )
                }
            }

            slots.forEach { slot ->
                val binding = plan.binding(slot.feedId) ?: return@forEach
                val viewportX = (slot.x.coerceIn(0f, 1f) * width).toInt()
                val viewportYTop = (slot.y.coerceIn(0f, 1f) * height).toInt()
                val viewportWidth = (slot.width.coerceIn(0.001f, 1f) * width).toInt().coerceAtLeast(1)
                val viewportHeight = (slot.height.coerceIn(0.001f, 1f) * height).toInt().coerceAtLeast(1)
                val viewportY = height - viewportYTop - viewportHeight

                GLES20.glViewport(
                    viewportX,
                    viewportY.coerceAtLeast(0),
                    viewportWidth.coerceAtMost(width - viewportX).coerceAtLeast(1),
                    viewportHeight.coerceAtMost(height).coerceAtLeast(1)
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
            val textures = texBuffer ?: return
            textures.clear()
            textures.put(u0)
            textures.put(v0)
            textures.put(u1)
            textures.put(v0)
            textures.put(u0)
            textures.put(v1)
            textures.put(u1)
            textures.put(v1)
            textures.position(0)
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
                selected = value.feedIds
            }
        }

        fun setSelectedFeedIds(value: List<String>) {
            selected = value.distinct()
            renderSlots = outputSlots.filter { it.feedId in selected }
        }

        fun setOutputSlots(value: List<OpenTiledMultiviewEngine.OutputSlot>) {
            outputSlots = value.distinctBy { it.feedId }.sortedBy { it.zIndex }
            renderSlots = outputSlots.filter { it.feedId in selected }
        }

        fun feedAt(normalizedX: Float, normalizedY: Float): String? =
            outputSlots
                .sortedByDescending { it.zIndex }
                .firstOrNull {
                    normalizedX >= it.x &&
                        normalizedX <= it.x + it.width &&
                        normalizedY >= it.y &&
                        normalizedY <= it.y + it.height
                }
                ?.feedId

        fun setSourceVideoSize(width: Int, height: Int) {
            sourceVideoWidth = width
            sourceVideoHeight = height
        }

        fun releaseOutput(releaseListener: Listener?) {
            surfaceTexture?.setOnFrameAvailableListener(null)
            releaseListener?.onOutputSurfaceReleased()
            outputSurface?.release()
            outputSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
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
