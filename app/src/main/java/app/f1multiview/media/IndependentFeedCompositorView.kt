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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val SYNTHETIC_FEED_COUNT = 3

/**
 * Experimental client-side compositor for independent decoded feeds.
 *
 * Each decoder writes to its own SurfaceTexture, while this view owns one EGL/GLES context and
 * one output surface. This is a clear synthetic proof harness only; it makes no claim about
 * protected playback, secure decoder surfaces, or production F1 manifest ingestion.
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
    @Volatile private var viewportLayout = ViewportLayout(
        id = "side-by-side",
        viewports = listOf(
            FeedViewport("LEFT", 0f, 0f, 1f / 3f, 1f, zIndex = 0),
            FeedViewport("CENTER", 1f / 3f, 0f, 1f / 3f, 1f, zIndex = 1),
            FeedViewport("RIGHT", 2f / 3f, 0f, 1f / 3f, 1f, zIndex = 2)
        )
    )
    private val syntheticFeedIds = listOf("LEFT", "CENTER", "RIGHT")

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

    /** Apply provider-neutral normalized viewports; UI controls remain outside the renderer. */
    fun setViewportLayout(layout: ViewportLayout) {
        require(layout.viewports.all { it.feedId in syntheticFeedIds }) {
            "Prototype layout references an unknown synthetic feed"
        }
        viewportLayout = layout
        requestRender()
    }

    fun renderedFrameCount(): Long = renderer.renderedFrameCount.get()

    /** GLES draw-call diagnostics; this is not display-present FPS. */
    fun drawDiagnostics(): String = renderer.drawDiagnostics()

    fun inputFrameCounts(): List<Long> = renderer.sourceFrameCount.map { it.get() }

    fun coalescedFrameNotifications(): List<Long> = renderer.sourceCoalescedNotifications.map { it.get() }

    /** Structured measurements for the on-device acceptance gate. */
    fun runtimeTextureMetrics(startedAtNs: Long): List<SyntheticMultiviewRuntimeGate.TextureMetrics> {
        val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
        return syntheticFeedIds.mapIndexed { index, feedId ->
            val firstNs = renderer.sourceFirstFrameAtNs[index].get()
            val lastNs = renderer.sourceLastFrameAtNs[index].get()
            SyntheticMultiviewRuntimeGate.TextureMetrics(
                feedId = feedId,
                textureUpdates = renderer.sourceFrameCount[index].get(),
                firstFrameLatencyMs = if (firstNs > 0L) ((firstNs - startedAtNs).coerceAtLeast(0L) / 1_000_000L) else null,
                lastUpdateAgeMs = if (lastNs > 0L) ((nowNs - lastNs).coerceAtLeast(0L) / 1_000_000L) else null,
                maxUpdateGapMs = renderer.sourceMaxArrivalGapNs[index].get() / 1_000_000L
            )
        }
    }

    fun runtimeDrawMetrics(): SyntheticMultiviewRuntimeGate.DrawMetrics {
        val firstNs = renderer.firstDrawAtNsSnapshot()
        val lastNs = renderer.lastDrawAtNsSnapshot()
        val count = renderer.renderedFrameCount.get()
        val rate = if (count > 1L && firstNs > 0L && lastNs > firstNs) {
            (count - 1L) * 1_000_000_000.0 / (lastNs - firstNs)
        } else null
        return SyntheticMultiviewRuntimeGate.DrawMetrics(
            drawFrames = count,
            drawRateFps = rate,
            maxDrawGapMs = renderer.maxDrawGapNsSnapshot() / 1_000_000L
        )
    }

    /** Reset all per-run counters on the GL thread before starting fresh decoder workers. */
    fun resetRuntimeMetrics(timeoutMs: Long = 2_000L): Boolean {
        val completed = CountDownLatch(1)
        return try {
            queueEvent {
                renderer.resetRuntimeMetrics()
                completed.countDown()
            }
            completed.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Measured input-frame updates, first-frame latency, update gaps, and PTS skew. Coalesced notifications are a surface-update diagnostic, not an authoritative decoder-drop count. */
    fun inputFrameDiagnostics(startedAtNs: Long): List<String> = (0 until SYNTHETIC_FEED_COUNT).map { index ->
        val count = renderer.sourceFrameCount[index].get()
        val firstNs = renderer.sourceFirstFrameAtNs[index].get()
        val lastNs = renderer.sourceLastFrameAtNs[index].get()
        val firstMs = if (firstNs == 0L) "pending" else "${(firstNs - startedAtNs).coerceAtLeast(0L) / 1_000_000L}ms"
        val fps = if (count > 1L && lastNs > firstNs) {
            String.format(java.util.Locale.US, "%.1f", (count - 1L) * 1_000_000_000.0 / (lastNs - firstNs))
        } else "warming"
        val surfaceTimestampMs = renderer.sourceSurfaceTimestampNs[index].get().let {
            if (it > 0L) "${it / 1_000_000L}ms" else "pending"
        }
        val maxGapMs = renderer.sourceMaxArrivalGapNs[index].get() / 1_000_000L
        val ageMs = if (lastNs == 0L) "never" else "${(android.os.SystemClock.elapsedRealtimeNanos() - lastNs).coerceAtLeast(0L) / 1_000_000L}ms"
        "feed${index + 1}: textureUpdates=$count firstFrame=$firstMs textureUpdatesPerSecond=$fps lastUpdateAge=$ageMs surfaceTs=$surfaceTimestampMs maxUpdateGap=${maxGapMs}ms coalescedNotifications=${renderer.sourceCoalescedNotifications[index].get()}"
    } + listOf(
        renderer.drawDiagnostics(),
        "surfaceTimestampSkew(notSyncVerdict)=${renderer.latestSurfaceTimestampSkewMs()}ms"
    )

    override fun onDetachedFromWindow() {
        queueEvent { renderer.releaseInputs(listener) }
        super.onDetachedFromWindow()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private val frameAvailable = Array(SYNTHETIC_FEED_COUNT) { AtomicBoolean(false) }
        val sourceFrameCount = Array(SYNTHETIC_FEED_COUNT) { AtomicLong(0L) }
        val sourceFirstFrameAtNs = Array(SYNTHETIC_FEED_COUNT) { AtomicLong(0L) }
        val sourceLastFrameAtNs = Array(SYNTHETIC_FEED_COUNT) { AtomicLong(0L) }
        val sourceSurfaceTimestampNs = Array(SYNTHETIC_FEED_COUNT) { AtomicLong(0L) }
        val sourceMaxArrivalGapNs = Array(SYNTHETIC_FEED_COUNT) { AtomicLong(0L) }
        val sourceCoalescedNotifications = Array(SYNTHETIC_FEED_COUNT) { AtomicLong(0L) }
        private val textureIds = IntArray(SYNTHETIC_FEED_COUNT)
        private val inputTextures = arrayOfNulls<SurfaceTexture>(SYNTHETIC_FEED_COUNT)
        private val inputSurfaces = arrayOfNulls<Surface>(SYNTHETIC_FEED_COUNT)
        private val transformMatrices = Array(SYNTHETIC_FEED_COUNT) { FloatArray(16) }
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
        private val firstDrawAtNs = AtomicLong(0L)
        private val lastDrawAtNs = AtomicLong(0L)
        private val maxDrawGapNs = AtomicLong(0L)

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
            GLES20.glGenTextures(SYNTHETIC_FEED_COUNT, textureIds, 0)
            for (index in 0 until SYNTHETIC_FEED_COUNT) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[index])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val texture = SurfaceTexture(textureIds[index])
                texture.setDefaultBufferSize(320, 180)
                texture.setOnFrameAvailableListener {
                    if (frameAvailable[index].getAndSet(true)) {
                        sourceCoalescedNotifications[index].incrementAndGet()
                    }
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
            for (index in 0 until SYNTHETIC_FEED_COUNT) {
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
                            if (timestampNs > 0L) sourceSurfaceTimestampNs[index].set(timestampNs)
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

            viewportLayout.viewports.sortedBy { it.zIndex }.forEach { viewport ->
                val index = syntheticFeedIds.indexOf(viewport.feedId)
                if (index < 0) return@forEach
                val viewportX = (viewport.x * width).toInt()
                val viewportTop = (viewport.y * height).toInt()
                val viewportWidth = (viewport.width * width).toInt().coerceAtLeast(1)
                val viewportHeight = (viewport.height * height).toInt().coerceAtLeast(1)
                val viewportY = height - viewportTop - viewportHeight
                GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[index])
                GLES20.glUniformMatrix4fv(matrixLocation, 1, false, transformMatrices[index], 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(textureLocation)
            val drawTimeNs = android.os.SystemClock.elapsedRealtimeNanos()
            val previousDrawNs = lastDrawAtNs.getAndSet(drawTimeNs)
            if (previousDrawNs > 0L) {
                val gapNs = drawTimeNs - previousDrawNs
                maxDrawGapNs.updateAndGet { previousMax -> maxOf(previousMax, gapNs) }
            }
            firstDrawAtNs.compareAndSet(0L, drawTimeNs)
            lastDrawAtNs.set(drawTimeNs)
            renderedFrameCount.incrementAndGet()
        }

        fun firstDrawAtNsSnapshot(): Long = firstDrawAtNs.get()
        fun lastDrawAtNsSnapshot(): Long = lastDrawAtNs.get()
        fun maxDrawGapNsSnapshot(): Long = maxDrawGapNs.get()

        fun resetRuntimeMetrics() {
            frameAvailable.forEach { it.set(false) }
            sourceFrameCount.forEach { it.set(0L) }
            sourceFirstFrameAtNs.forEach { it.set(0L) }
            sourceLastFrameAtNs.forEach { it.set(0L) }
            sourceSurfaceTimestampNs.forEach { it.set(0L) }
            sourceMaxArrivalGapNs.forEach { it.set(0L) }
            sourceCoalescedNotifications.forEach { it.set(0L) }
            renderedFrameCount.set(0L)
            firstDrawAtNs.set(0L)
            lastDrawAtNs.set(0L)
            maxDrawGapNs.set(0L)
        }

        fun drawDiagnostics(): String {
            val count = renderedFrameCount.get()
            val first = firstDrawAtNs.get()
            val last = lastDrawAtNs.get()
            val rate = if (count > 1L && last > first) {
                String.format(java.util.Locale.US, "%.1f", (count - 1L) * 1_000_000_000.0 / (last - first))
            } else "warming"
            return "GLES draw frames=$count drawFrameRate=$rate/s maxDrawGap=${maxDrawGapNs.get() / 1_000_000L}ms (not display-present FPS)"
        }

        fun latestSurfaceTimestampSkewMs(): Long {
            val timestamps = sourceSurfaceTimestampNs.map { it.get() }
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
            for (index in 0 until SYNTHETIC_FEED_COUNT) {
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
