package app.f1multiview

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import app.f1multiview.media.AppLogger
import app.f1multiview.media.IndependentFeedCompositorView
import app.f1multiview.media.SyntheticFeedDecoder
import app.f1multiview.media.SyntheticMultiviewClipFactory
import java.util.concurrent.Executors

/**
 * Isolated proof harness for independent clear video feeds.
 *
 * This activity is reachable only from the debug Settings panel. It does not use F1 endpoints,
 * credentials, Media3 players, or DRM-protected content. It generates two short local H.264
 * clips, decodes each with a distinct MediaCodec, and composites both decoder Surfaces through
 * one GLES view.
 */
class SyntheticMultiviewPrototypeActivity : Activity(), IndependentFeedCompositorView.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "synthetic-multiview-setup").apply { isDaemon = true }
    }
    private lateinit var compositor: IndependentFeedCompositorView
    private lateinit var statusView: TextView
    private var clips: SyntheticMultiviewClipFactory.ClipPair? = null
    @Volatile private var currentSurfaces: List<Surface>? = null
    private val pipelineLock = Any()
    private val pipelines = mutableListOf<SyntheticFeedDecoder>()
    private val statusLines = linkedMapOf<String, String>()
    private var starting = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this)
        compositor = IndependentFeedCompositorView(this)
        root.addView(
            compositor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(18, 14, 18, 14)
            setBackgroundColor(0xD9000000.toInt())
            text = "SYNTHETIC MULTIVIEW PROTOTYPE\nGenerating two clear H.264 test clips…"
            isFocusable = true
            contentDescription = "Synthetic multiview diagnostics. Press Back to exit."
        }
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = 12
            rightMargin = 12
            bottomMargin = 12
        }
        root.addView(statusView, statusParams)
        setContentView(root)
        compositor.setListener(this)

        worker.execute {
            try {
                val generated = SyntheticMultiviewClipFactory.createPair(this)
                mainHandler.post {
                    if (destroyed) return@post
                    clips = generated
                    statusLines["source"] = "Generated local H.264 clips: ${generated.left.length()} / ${generated.right.length()} bytes"
                    renderStatus()
                    startIfReady()
                }
            } catch (failure: Throwable) {
                mainHandler.post {
                    if (destroyed) return@post
                    statusLines["source-error"] =
                        "FIXTURE ERROR ${failure.javaClass.simpleName}: ${failure.message ?: "unknown"}"
                    renderStatus()
                }
            }
        }
    }

    override fun onInputSurfacesReady(surfaces: List<Surface>) {
        mainHandler.post {
            if (destroyed) return@post
            currentSurfaces = surfaces
            statusLines["surfaces"] = "Compositor input surfaces ready: ${surfaces.size}"
            renderStatus()
            startIfReady()
        }
    }

    override fun onInputSurfacesReleased() {
        // Stop decoders before their output Surfaces are released by the GL renderer.
        stopDecodersAndWait()
        mainHandler.post {
            currentSurfaces = null
            statusLines["surfaces"] = "Compositor surfaces recreated; waiting for new inputs"
            renderStatus()
        }
    }

    private fun startIfReady() {
        if (destroyed || starting || clips == null || currentSurfaces?.size != 2) return
        synchronized(pipelineLock) {
            if (pipelines.isNotEmpty()) return
            starting = true
            val pair = requireNotNull(clips)
            val surfaces = requireNotNull(currentSurfaces)
            val left = SyntheticFeedDecoder("LEFT", pair.left, surfaces[0], ::onDecoderStatus)
            val right = SyntheticFeedDecoder("RIGHT", pair.right, surfaces[1], ::onDecoderStatus)
            pipelines += left
            pipelines += right
            left.start()
            right.start()
            starting = false
        }
        statusLines["pipeline"] = "Two independent MediaCodec decoder pipelines started"
        renderStatus()
        AppLogger.i("SyntheticMultiview", "prototype started with two clear local H.264 feeds")
    }

    private fun onDecoderStatus(line: String) {
        mainHandler.post {
            if (destroyed) return@post
            val key = line.substringBefore(' ')
            statusLines[key] = line
            renderStatus()
            AppLogger.i("SyntheticMultiview", line)
        }
    }

    private fun renderStatus() {
        val lines = buildList {
            add("SYNTHETIC MULTIVIEW · CLEAR CONTENT ONLY")
            add("Two MediaCodec decoders → two SurfaceTextures → one GLES compositor")
            add("Compositor render ticks: ${compositor.renderedFrameCount()}")
            statusLines.values.forEach(::add)
            add("Press Back to exit")
        }
        statusView.text = lines.joinToString("\n")
    }

    private fun stopDecodersAndWait() {
        synchronized(pipelineLock) {
            pipelines.forEach { it.stopAndJoin(500L) }
            pipelines.clear()
            starting = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (::compositor.isInitialized) compositor.onResume()
    }

    override fun onPause() {
        stopDecodersAndWait()
        if (::compositor.isInitialized) compositor.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        stopDecodersAndWait()
        if (::compositor.isInitialized) compositor.setListener(null)
        worker.shutdownNow()
        super.onDestroy()
    }
}
