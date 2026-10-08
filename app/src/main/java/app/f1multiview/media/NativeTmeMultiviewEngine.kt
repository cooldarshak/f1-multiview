package app.f1multiview.media

import android.content.Context
import android.view.Surface
import app.f1multiview.core.playback.TiledMultiviewSession
import app.f1multiview.model.StreamSource

/**
 * Physical owner for the independent-feed TME topology.
 *
 * The ownership graph is intentionally fixed:
 * N CMAF tile feeds -> one GPAC merger -> one TmeNativeDecoder -> one Surface.
 */
class NativeTmeMultiviewEngine(
    context: Context
) {
    private val merger = GpacNativeTmeMerger()
    private val decoder = TmeNativeDecoder()
    private val coordinatorContext = context.applicationContext
    private var coordinator: TmeCmafCoordinator? = null
    private var session: TiledMultiviewSession? = null
    private var source: StreamSource? = null
    private var attachedView: OpenTiledCompositorView? = null
    private var configured = false
    private var currentSources: List<TmeTileSource> = emptyList()
    private var outputSurface: android.view.Surface? = null

    fun canHandle(session: TiledMultiviewSession): Boolean =
        merger.available &&
            session.isUsable &&
            session.feeds.size >= 2 &&
            session.tileCountHorizontal != null &&
            session.tileCountHorizontal > 0 &&
            session.tileCountVertical != null &&
            session.tileCountVertical > 0 &&
            session.feeds.all {
                !it.url.isNullOrBlank() &&
                    it.tileIndex != null &&
                    it.tileRow != null &&
                    it.tileColumn != null &&
                    it.tileIndex < session.tileCountHorizontal * session.tileCountVertical &&
                    it.tileColumn < session.tileCountHorizontal &&
                    it.tileRow < session.tileCountVertical
            }

    fun prepare(
        session: TiledMultiviewSession,
        source: StreamSource
    ): Boolean {
        if (!canHandle(session)) return false
        stop()
        this.session = session
        this.source = source
        this.configured = false
        return true
    }

    fun attach(view: OpenTiledCompositorView) {
        val current = session ?: return
        attachedView = view
        view.setSession(current)
        view.setSelectedFeedIds(current.feedIds)
        view.setListener(object : OpenTiledCompositorView.Listener {
            override fun onOutputSurfaceReady(surface: Surface) {
                start(surface)
            }

            override fun onOutputSurfaceReleased() {
                coordinator?.stop()
                coordinator = null
                decoder.close()
                merger.release()
                configured = false
                outputSurface = null
            }
        })
    }

    fun selectFeeds(feedIds: List<String>) {
        attachedView?.setSelectedFeedIds(feedIds.distinct())
        if (merger.available && configured) {
            merger.updateSelection(feedIds.toSet())
        }
    }

    fun setSlots(slots: List<OpenTiledMultiviewEngine.OutputSlot>) {
        attachedView?.setOutputSlots(slots)
    }

    fun play() {
        val surface = outputSurface ?: return
        if (coordinator == null && currentSources.isNotEmpty()) {
            coordinator = TmeCmafCoordinator(
                coordinatorContext,
                merger,
                decoder,
                source?.requestHeaders.orEmpty(),
                session?.tileCountHorizontal?.let { it * requireNotNull(session?.tileWidth) },
                session?.tileCountVertical?.let { it * requireNotNull(session?.tileHeight) }
            ).also { c ->
                c.start(
                    currentSources,
                    surface,
                    onError = { error ->
                        AppLogger.e("TME", "Native CMAF pipeline failed: " + error.message)
                    },
                    onDecoderReady = { configured = true }
                )
            }
        }
        attachedView?.requestRender()
    }

    fun pause() {
        coordinator?.stop()
    }

    fun release() {
        stop()
        decoder.close()
        merger.release()
        attachedView = null
        session = null
        source = null
        currentSources = emptyList()
        outputSurface = null
    }

    fun diagnostics(): Map<String, String> {
        val telemetry = TmeRuntimeTelemetry(
            backend = "OPEN_TME_NATIVE_PIPELINE",
            logicalFeedCount = session?.feeds?.size ?: 0,
            selectedFeedCount = session?.feeds?.size ?: 0,
            inputTileStreams = session?.feeds?.size ?: 0,
            mergedVideoStreams = if (configured) 1 else 0,
            mediaCodecInstances = if (decoder.telemetry().configured) 1 else 0,
            outputSurfaces = if (decoder.telemetry().outputSurfaceAttached) 1 else 0,
            droppedSegments = 0,
            mergeLatencyMs = 0,
            decoderRecreationCount = decoder.telemetry().decoderRecreationCount
        )
        return mapOf(
            "backend" to "NATIVE_TME",
            "available" to merger.available.toString(),
            "prepared" to (session != null).toString(),
            "configured" to configured.toString(),
            "logicalFeedCount" to (session?.feeds?.size ?: 0).toString(),
            "physicalMerger" to if (merger.available) "GPAC_HEVCMERGE" else "UNAVAILABLE",
            "inputTileStreams" to telemetry.inputTileStreams.toString(),
            "mergedVideoStreams" to telemetry.mergedVideoStreams.toString(),
            "mediaCodecInstances" to telemetry.mediaCodecInstances.toString(),
            "outputSurfaces" to telemetry.outputSurfaces.toString(),
            "singleDecoderInvariant" to telemetry.singleDecoderInvariant.toString(),
            "decoderRecreationCount" to telemetry.decoderRecreationCount.toString()
        )
    }

    private fun start(surface: Surface) {
        val currentSession = session ?: return
        val currentSource = source ?: return
        if (coordinator != null) return

        val sources = currentSession.feeds.mapIndexed { index, feed ->
            TmeTileSource(
                feedId = currentSession.feedIds[index],
                url = requireNotNull(feed.url),
                tileIndex = requireNotNull(feed.tileIndex),
                row = requireNotNull(feed.tileRow),
                column = requireNotNull(feed.tileColumn),
                tileWidth = requireNotNull(currentSession.tileWidth),
                tileHeight = requireNotNull(currentSession.tileHeight),
                requestHeaders = currentSource.requestHeaders
            )
        }

        currentSources = sources
        outputSurface = surface
        coordinator = TmeCmafCoordinator(
            coordinatorContext,
            merger,
            decoder,
            currentSource.requestHeaders,
            currentSession.tileCountHorizontal?.let { it * requireNotNull(currentSession.tileWidth) },
            currentSession.tileCountVertical?.let { it * requireNotNull(currentSession.tileHeight) }
        ).also { c ->
            c.start(
                sources,
                surface,
                onError = { error ->
                    AppLogger.e("TME", "Native CMAF pipeline failed: " + error.message)
                },
                onDecoderReady = {
                    configured = true
                }
            )
        }
    }

    private fun stop() {
        coordinator?.stop()
        coordinator = null
    }
}
