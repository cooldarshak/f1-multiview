package app.f1multiview

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * credentials, Media3 players, or DRM-protected content. It generates three short local H.264
 * clips, decodes each with a distinct MediaCodec, and composites all decoder Surfaces through
 * one GLES view.
 */
class SyntheticMultiviewPrototypeActivity : Activity(), IndependentFeedCompositorView.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prototypeStartedAtNs = SystemClock.elapsedRealtimeNanos()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "synthetic-multiview-setup").apply { isDaemon = true }
    }
    private lateinit var compositor: IndependentFeedCompositorView
    private lateinit var statusView: TextView
    private var clips: SyntheticMultiviewClipFactory.ClipSet? = null
    @Volatile private var currentSurfaces: List<Surface>? = null
    private val pipelineLock = Any()
    private val pipelines = mutableListOf<SyntheticFeedDecoder>()
    private val statusLines = linkedMapOf<String, String>()
    private var starting = false
    private var destroyed = false
    private val diagnosticTicker = object : Runnable {
        override fun run() {
            if (destroyed || !::compositor.isInitialized) return
            compositor.inputFrameDiagnostics(prototypeStartedAtNs).forEach {
                AppLogger.i("SyntheticMultiviewMetrics", it)
            }
            renderStatus()
            mainHandler.postDelayed(this, 5_000L)
        }
    }

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
            text = "SYNTHETIC MULTIVIEW PROTOTYPE\nGenerating three clear H.264 test clips…"
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
                val generated = SyntheticMultiviewClipFactory.createSet(this)
                mainHandler.post {
                    if (destroyed) return@post
                    clips = generated
                    statusLines["source"] = "Generated local H.264 clips: ${generated.files.joinToString(" / ") { it.length().toString() }} bytes"
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
        if (destroyed || starting || clips == null || currentSurfaces?.size != 3) return
        synchronized(pipelineLock) {
            if (pipelines.isNotEmpty()) return
            starting = true
            val files = requireNotNull(clips).files
            val surfaces = requireNotNull(currentSurfaces)
            val labels = listOf("LEFT", "CENTER", "RIGHT")
            // Give every decoder one common epoch slightly in the future so setup time does not
            // create a different playback clock for each feed.
            val sharedPlaybackAnchorNs = System.nanoTime() + 500_000_000L
            files.zip(surfaces).forEachIndexed { index, (file, surface) ->
                pipelines += SyntheticFeedDecoder(
                    labels[index], file, surface, sharedPlaybackAnchorNs, ::onDecoderStatus
                )
            }
            pipelines.toList().forEach(SyntheticFeedDecoder::start)
            starting = false
        }
        statusLines["pipeline"] = "Three MediaCodec decoders started on one shared playback timeline"
        renderStatus()
        AppLogger.i("SyntheticMultiview", "prototype started with three clear local H.264 feeds")
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
            add("Three MediaCodec decoders → three SurfaceTextures → one GLES compositor")
            compositor.inputFrameDiagnostics(prototypeStartedAtNs).forEach(::add)
            add(compositor.drawDiagnostics())
            statusLines.values.forEach(::add)
            add("Press Back to exit")
        }
        statusView.text = lines.joinToString("\n")
    }

    private fun stopDecodersAndWait() {
        synchronized(pipelineLock) {
            val stuck = pipelines.filterNot { it.stopAndJoin(1_000L) }
            if (stuck.isNotEmpty()) {
                val labels = stuck.joinToString(",") { it.label }
                AppLogger.e("SyntheticMultiview", "DECODER_STOP_TIMEOUT count=${stuck.size} decoders=$labels")
                mainHandler.post {
                    statusLines["decoder-stop"] = "DECODER_STOP_TIMEOUT count=${stuck.size}; see app logs"
                    renderStatus()
                }
            }
            pipelines.clear()
            starting = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (::compositor.isInitialized) {
            compositor.onResume()
            // Re-deliver existing surfaces when EGL was retained across pause.
            compositor.setListener(this)
            mainHandler.removeCallbacks(diagnosticTicker)
            mainHandler.postDelayed(diagnosticTicker, 5_000L)
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(diagnosticTicker)
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
