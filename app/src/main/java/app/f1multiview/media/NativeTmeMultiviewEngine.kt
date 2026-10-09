package app.f1multiview.media

import android.content.Context
import android.view.Surface
import app.f1multiview.core.playback.TiledMultiviewSession
import app.f1multiview.data.f1tv.TmeTopology
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
    private var selectedFeedIds: List<String> = emptyList()
    private var currentSources: List<TmeTileSource> = emptyList()
    private var outputSurface: android.view.Surface? = null

    fun canHandle(session: TiledMultiviewSession): Boolean {
        // No current session metadata proves that distinct F1 feeds are spatial HEVC
        // tiles from the same encoded picture. GPAC hevcmerge cannot combine arbitrary
        // camera videos. A genuine single-mosaic URL is handled by OpenTiledMultiviewBackend,
        // which uses one normal Media3 player; it must not be sent through this merger.
        val reason = when (session.topology) {
            TmeTopology.SINGLE_MOSAIC_SOURCE ->
                "single-mosaic input belongs to OpenTiledMultiviewBackend"
            TmeTopology.INDEPENDENT_FEED_SOURCES ->
                "independent F1 feeds are not proven compatible spatial HEVC tiles"
            TmeTopology.UNKNOWN ->
                "TME input topology is unknown"
        }
        AppLogger.w("TME", "NATIVE_TME_CAPABILITY_REJECT topology=${session.topology} reason=$reason")
        return false
    }

    fun prepare(
        session: TiledMultiviewSession,
        source: StreamSource
    ): Boolean {
        if (!canHandle(session)) return false
        stop()
        // Re-prepare means a new physical playback graph. Pause/resume deliberately
        // keeps the existing decoder, but changing the TME session must not reuse it.
        decoder.close()
        merger.release()
        this.session = session
        this.source = source
        this.selectedFeedIds = session.feedIds
        AppLogger.i("TME", "PREPARE backend=NATIVE_TME inputFeeds=${session.feeds.size} tiles=${session.tileCountHorizontal}x${session.tileCountVertical}")
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
        selectedFeedIds = feedIds.distinct().ifEmpty { session?.feedIds.orEmpty() }
        attachedView?.setSelectedFeedIds(selectedFeedIds)
        if (merger.available && configured) {
            merger.updateSelection(selectedFeedIds.toSet())
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
        coordinator = null
    }

    fun release() {
        stop()
        decoder.close()
        merger.release()
        attachedView = null
        session = null
        source = null
        selectedFeedIds = emptyList()
        currentSources = emptyList()
        outputSurface = null
    }

    fun diagnostics(): Map<String, String> {
        val telemetry = TmeRuntimeTelemetry(
            backend = "OPEN_TME_NATIVE_PIPELINE",
            logicalFeedCount = session?.feeds?.size ?: 0,
            selectedFeedCount = selectedFeedIds.size,
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
            "available" to (session?.let(::canHandle) == true).toString(),
            "gpacLibraryPresent" to merger.available.toString(),
            "capabilityReason" to if (session == null)
                "No TME session has been prepared"
            else if (session.topology != TmeTopology.SINGLE_MOSAIC_SOURCE)
                "Independent feed URLs are not proven compatible spatial HEVC tiles"
            else
                "Single-source mosaic sessions belong to OpenTiledMultiviewBackend, not this independent-feed merger",
            "prepared" to (session != null).toString(),
            "configured" to configured.toString(),
            "logicalFeedCount" to (session?.feeds?.size ?: 0).toString(),
            "physicalMerger" to if (session?.topology == TmeTopology.INDEPENDENT_FEED_SOURCES)
                "NOT_COMPATIBLE_UNPROVEN"
            else if (merger.available) "GPAC_HEVCMERGE_LIBRARY_ONLY" else "UNAVAILABLE",
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
        AppLogger.i("TME", "GRAPH_CREATE backend=NATIVE_TME inputFeeds=${sources.size} merger=1 mediacodec=1 outputSurfaces=1")
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
                    val telemetry = decoder.telemetry()
                    AppLogger.i("TME", "RUNTIME_INVARIANT backend=NATIVE_TME inputFeeds=${sources.size} gpacMerger=1 mergedVideoStreams=1 mediaCodecInstances=${if (telemetry.configured) 1 else 0} outputSurfaces=${if (telemetry.outputSurfaceAttached) 1 else 0} decoderRecreations=${telemetry.decoderRecreationCount}")
                }
            )
        }
    }

    private fun stop() {
        coordinator?.stop()
        coordinator = null
    }
}
