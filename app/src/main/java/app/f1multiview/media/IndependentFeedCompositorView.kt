package app.f1multiview.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Experimental client-side compositor for independent decoded feeds.
 *
 * Each decoder writes to its own SurfaceTexture, while this view owns one EGL/GLES context and
 * one output surface. This is intentionally separate from OpenTiledCompositorView: that class
 * consumes one already-composed mosaic stream and must keep its existing protected-playback
 * behavior unchanged.
 *
 * This prototype is clear-content-only. It makes no claim about Widevine secure-surface support.
 */
internal class IndependentFeedCompositorView(context: Context) : GLSurfaceView(context) {
    interface Listener {
        fun onInputSurfacesReady(surfaces: List<Surface>)
        fun onInputSurfacesReleased()
    }

    private val renderer = Renderer()
    @Volatile private var listener: Listener? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = false
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
    }

    fun setListener(value: Listener?) {
        listener = value
        queueEvent { renderer.notifyListener(value) }
    }

    fun renderedFrameCount(): Long = renderer.renderedFrameCount.get()

    fun inputFrameCounts(): List<Long> = renderer.sourceFrameCount.map { it.get() }

    /** Measured input-frame updates, first-frame latency, and observed update rate. */
    fun inputFrameDiagnostics(startedAtNs: Long): List<String> = (0..2).map { index ->
        val count = renderer.sourceFrameCount[index].get()
        val firstNs = renderer.sourceFirstFrameAtNs[index].get()
        val lastNs = renderer.sourceLastFrameAtNs[index].get()
        val firstMs = if (firstNs == 0L) "pending" else "${(firstNs - startedAtNs).coerceAtLeast(0L) / 1_000_000L}ms"
        val fps = if (count > 1L && lastNs > firstNs) {
            String.format(java.util.Locale.US, "%.1f", (count - 1L) * 1_000_000_000.0 / (lastNs - firstNs))
        } else "warming"
        val mediaTimestampMs = renderer.sourceMediaTimestampNs[index].get().let {
            if (it > 0L) "${it / 1_000_000L}ms" else "pending"
        }
        val maxGapMs = renderer.sourceMaxArrivalGapNs[index].get() / 1_000_000L
        "feed${index + 1}: frames=$count firstFrame=$firstMs observedFps=$fps mediaTs=$mediaTimestampMs maxArrivalGap=${maxGapMs}ms"
    } + listOf(
        "latestMediaTimestampSkew=${renderer.latestMediaTimestampSkewMs()}ms"
    )

    override fun onDetachedFromWindow() {
        queueEvent { renderer.releaseInputs(listener) }
        super.onDetachedFromWindow()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private val frameAvailable = Array(3) { AtomicBoolean(false) }
        val sourceFrameCount = Array(3) { AtomicLong(0L) }
        val sourceFirstFrameAtNs = Array(3) { AtomicLong(0L) }
        val sourceLastFrameAtNs = Array(3) { AtomicLong(0L) }
        val sourceMediaTimestampNs = Array(3) { AtomicLong(0L) }
        val sourceMaxArrivalGapNs = Array(3) { AtomicLong(0L) }
        private val textureIds = IntArray(3)
        private val inputTextures = arrayOfNulls<SurfaceTexture>(3)
        private val inputSurfaces = arrayOfNulls<Surface>(3)
        private val transformMatrices = Array(3) { FloatArray(16) }
        private val vertexData = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val textureData = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
        private var program = 0
        private var positionLocation = -1
        private var textureLocation = -1
        private var matrixLocation = -1
        private var samplerLocation = -1
        private var width = 0
        private var height = 0
        val renderedFrameCount = AtomicLong(0L)

        private val vertexShader = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
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

        override fun onSurfaceCreated(
            gl: javax.microedition.khronos.opengles.GL10?,
            config: javax.microedition.khronos.egl.EGLConfig?
        ) {
            releaseInputs(listener)
            GLES20.glClearColor(0.02f, 0.02f, 0.03f, 1f)
            program = linkProgram(vertexShader, fragmentShader)
            GLES20.glUseProgram(program)
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
            textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
            matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
            samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
            GLES20.glGenTextures(3, textureIds, 0)
            for (index in 0..2) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[index])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val texture = SurfaceTexture(textureIds[index])
                texture.setDefaultBufferSize(320, 180)
                texture.setOnFrameAvailableListener {
                    frameAvailable[index].set(true)
                }
                inputTextures[index] = texture
                inputSurfaces[index] = Surface(texture)
            }
            listener?.onInputSurfacesReady(inputSurfaces.filterNotNull())
        }

        override fun onSurfaceChanged(
            gl: javax.microedition.khronos.opengles.GL10?,
            w: Int,
            h: Int
        ) {
            width = w
            height = h
            GLES20.glViewport(0, 0, w, h)
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            for (index in 0..2) {
                if (frameAvailable[index].compareAndSet(true, false)) {
                    runCatching {
                        inputTextures[index]?.updateTexImage()
                        inputTextures[index]?.getTransformMatrix(transformMatrices[index])
                        val frameTimeNs = android.os.SystemClock.elapsedRealtimeNanos()
                        val previousArrivalNs = sourceLastFrameAtNs[index].getAndSet(frameTimeNs)
                        if (previousArrivalNs > 0L) {
                            val gapNs = frameTimeNs - previousArrivalNs
                            sourceMaxArrivalGapNs[index].updateAndGet { previousMax -> maxOf(previousMax, gapNs) }
                        }
                        sourceFirstFrameAtNs[index].compareAndSet(0L, frameTimeNs)
                        inputTextures[index]?.timestamp?.let { timestampNs ->
                            if (timestampNs > 0L) sourceMediaTimestampNs[index].set(timestampNs)
                        }
                        sourceFrameCount[index].incrementAndGet()
                    }
                }
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (width <= 0 || height <= 0 || program == 0) return

            vertexData.position(0)
            textureData.position(0)
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexData)
            GLES20.glEnableVertexAttribArray(textureLocation)
            GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 0, textureData)
            GLES20.glUniform1i(samplerLocation, 0)

            for (index in 0..2) {
                val viewportWidth = if (index == 2) width - (width / 3) * 2 else width / 3
                val viewportX = (width / 3) * index
                GLES20.glViewport(viewportX, 0, viewportWidth.coerceAtLeast(1), height)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[index])
                GLES20.glUniformMatrix4fv(matrixLocation, 1, false, transformMatrices[index], 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(textureLocation)
            renderedFrameCount.incrementAndGet()
        }

        fun latestMediaTimestampSkewMs(): Long {
            val timestamps = sourceMediaTimestampNs.map { it.get() }
            if (timestamps.any { it <= 0L }) return -1L
            val minimum = timestamps.minOrNull() ?: return -1L
            val maximum = timestamps.maxOrNull() ?: return -1L
            return (maximum - minimum) / 1_000_000L
        }

        fun notifyListener(value: Listener?) {
            if (value != null && inputSurfaces.all { it != null }) {
                value.onInputSurfacesReady(inputSurfaces.filterNotNull())
            }
        }

        fun releaseInputs(callback: Listener?) {
            val hadInputs = inputSurfaces.any { it != null } || inputTextures.any { it != null }
            // Notify the owner before releasing output Surfaces so it can stop MediaCodec
            // instances first. Surface lifetime must outlive every decoder targeting it.
            if (hadInputs) callback?.onInputSurfacesReleased()
            inputSurfaces.forEach { surface -> runCatching { surface?.release() } }
            inputTextures.forEach { texture -> runCatching { texture?.release() } }
            for (index in 0..2) {
                inputSurfaces[index] = null
                inputTextures[index] = null
                frameAvailable[index].set(false)
            }
        }

        private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) {
                    val error = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    error("Unable to compile prototype shader: $error")
                }
                return shader
            }
            val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val linked = GLES20.glCreateProgram()
            GLES20.glAttachShader(linked, vertex)
            GLES20.glAttachShader(linked, fragment)
            GLES20.glLinkProgram(linked)
            val status = IntArray(1)
            GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            if (status[0] == 0) {
                val error = GLES20.glGetProgramInfoLog(linked)
                GLES20.glDeleteProgram(linked)
                error("Unable to link prototype shader: $error")
            }
            return linked
        }

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }
    }

}
