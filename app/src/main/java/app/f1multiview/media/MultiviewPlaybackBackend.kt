package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewSession
import app.f1multiview.data.f1tv.TmePlayback
import app.f1multiview.data.f1tv.TmeTopology
import app.f1multiview.model.StreamSource
import android.content.Context

/**
 * Capability boundary between the app's multiview orchestration and the physical
 * playback implementation.
 *
 * This interface deliberately does not pretend that a Media3 collection of players
 * is equivalent to Tiledmedia's native single-player backend. A native backend must
 * own the tiled retrieval/ABR/decoder/rendering path.
 */
enum class MultiviewBackendKind {
    NATIVE_TME,
    OPEN_TME,
    MEDIA3_MULTI_PLAYER_FALLBACK
}

data class MultiviewBackendStatus(
    val kind: MultiviewBackendKind,
    val available: Boolean,
    val singlePlayer: Boolean,
    val reason: String
)

interface MultiviewPlaybackBackend {
    val status: MultiviewBackendStatus

    /**
     * Returns true only when this backend can actually consume the supplied TME
     * session and own its physical playback pipeline.
     */
    fun canHandle(session: TiledMultiviewSession): Boolean
}

/**
 * The real Tiledmedia SDK is proprietary and is not bundled with this project.
 *
 * Keeping this as an explicit unavailable backend is intentional. It prevents the
 * rest of the app from silently treating TME JSON as though it were a playable
 * tiled stream.
 */
class NativeTmePlaybackBackend(
    context: Context
) : MultiviewPlaybackBackend {
    private val appContext = context.applicationContext
    @Volatile private var preflightInFlight = false
    @Volatile private var preflightKey: String? = null
    @Volatile private var preflightReport: TmeCmafPreflightReport? = null

    // Do not construct the physical engine merely to query backend capability/status.
    // The engine owns MediaCodec/native resources and is only needed when playback is
    // actually requested. This also keeps capability tests independent of Android
    // framework objects.
    private val engine by lazy(LazyThreadSafetyMode.NONE) {
        NativeTmeMultiviewEngine(appContext)
    }

    override val status: MultiviewBackendStatus
        get() {
            val gpacPresent = runCatching { GpacNativeTmeMerger().available }.getOrDefault(false)
            return MultiviewBackendStatus(
                kind = MultiviewBackendKind.NATIVE_TME,
                // Native library presence is not proof that this backend can merge
                // unrelated F1 camera feeds or decode their protected CMAF samples.
                available = false,
                singlePlayer = false,
                reason = if (gpacPresent)
                    "GPAC hevcmerge is present, but multi-URL CMAF preflight must prove HEVC tile structure and secure DRM compatibility before native playback can be enabled"
                else
                    "GPAC native merger is unavailable; multi-URL topology, HEVC tile compatibility and secure DRM handling remain unproven"
            )
        }

    override fun canHandle(session: TiledMultiviewSession): Boolean = engine.canHandle(session)

    /**
     * Starts a one-shot, metadata-only probe of the first HLS/CMAF segment for each
     * unique source URL. It never decrypts protected samples or enables the merger.
     * Results are retained for the existing diagnostics screen/logging.
     */
    @Synchronized
    fun inspectInputs(session: TiledMultiviewSession, source: StreamSource) {
        if (session.feeds.size < 2) return
        val key = (session.contentId?.toString() ?: "unknown") + ":" +
            session.feedIds.joinToString("|") + ":" + session.feeds.map { it.url.orEmpty() }.joinToString("|")
        if (preflightKey == key || preflightInFlight) return
        preflightKey = key
        preflightInFlight = true
        Thread({
            try {
                val reader = TmeCmafFeedReader(appContext, source.requestHeaders)
                val evidenceByUrl = mutableMapOf<String, TmeCmafFeedEvidence>()
                val evidence = session.feeds.mapIndexed { index, feed ->
                    val url = feed.url?.takeIf(String::isNotBlank)
                    if (url == null) {
                        TmeCmafFeedEvidence(
                            feedId = session.feedIds[index],
                            mimeType = null,
                            width = null,
                            height = null,
                            codecConfigFingerprint = null,
                            encrypted = false,
                            sampleCount = 0,
                            firstSampleTimeUs = null,
                            firstSampleIsSync = null,
                            inspectionError = "Feed URL is missing"
                        )
                    } else {
                        evidenceByUrl.getOrPut(url) {
                            reader.inspectFirstSegment(url, session.feedIds[index])
                        }.copy(feedId = session.feedIds[index])
                    }
                }
                val report = TmeCmafPreflight.assess(session, evidence)
                preflightReport = report
                AppLogger.i(
                    "TME",
                    "CMAF_PREFLIGHT status=${report.status} nativeMergeEligible=${report.nativeMergeEligible} " +
                        "topology=${session.topology} feeds=${evidence.size} summary=${report.summary}"
                )
                evidence.forEach { item ->
                    AppLogger.i(
                        "TME",
                        "CMAF_FEED_EVIDENCE feed=${item.feedId} mime=${item.mimeType ?: "unknown"} " +
                            "size=${item.width ?: 0}x${item.height ?: 0} encrypted=${item.encrypted} " +
                            "encryptionMethod=${item.encryptionMethod ?: "none-or-unknown"} samples=${item.sampleCount} " +
                            "firstPtsUs=${item.firstSampleTimeUs ?: -1L} firstSync=${item.firstSampleIsSync ?: "unknown"} " +
                            "codecConfigFingerprint=${item.codecConfigFingerprint?.take(16) ?: "none"} " +
                            "error=${item.inspectionError ?: "none"}"
                    )
                }
            } catch (error: Throwable) {
                AppLogger.e("TME", "CMAF_PREFLIGHT_FAILED type=${error.javaClass.simpleName} message=${error.message}")
            } finally {
                preflightInFlight = false
            }
        }, "f1-cmaf-preflight").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun prepare(session: TiledMultiviewSession, source: StreamSource): Boolean =
        engine.prepare(session, source)

    fun attach(view: OpenTiledCompositorView) = engine.attach(view)

    fun selectFeeds(feedIds: List<String>) = engine.selectFeeds(feedIds)

    fun setSlots(slots: List<OpenTiledMultiviewEngine.OutputSlot>) = engine.setSlots(slots)

    fun play() = engine.play()
    fun pause() = engine.pause()
    fun release() = engine.release()
    fun diagnostics(): Map<String, String> {
        val report = preflightReport
        return engine.diagnostics() + mapOf(
            "cmafPreflightInFlight" to preflightInFlight.toString(),
            "cmafPreflightStatus" to (report?.status?.name ?: "NOT_RUN"),
            "cmafPreflightNativeMergeEligible" to (report?.nativeMergeEligible?.toString() ?: "false"),
            "cmafPreflightSummary" to (report?.summary ?: "No multi-URL CMAF preflight has completed")
        )
    }
}

/**
 * Existing Media3 path. It remains the safe fallback until a licensed native TME
 * backend is supplied. This backend must never be described as single-player.
 */
class Media3MultiPlayerFallbackBackend : MultiviewPlaybackBackend {
    override val status = MultiviewBackendStatus(
        kind = MultiviewBackendKind.MEDIA3_MULTI_PLAYER_FALLBACK,
        available = true,
        singlePlayer = false,
        reason = "Uses the existing Media3 logical-feed/physical-decoder architecture"
    )

    override fun canHandle(session: TiledMultiviewSession): Boolean = true
}


/**
 * Our open-source tiled backend. It is intentionally narrower than the proprietary
 * Tiledmedia SDK: it only claims native single-player capability when the provider
 * gives us one actual mosaic/tiled media URL.
 */
class OpenTiledMultiviewBackend(
    private val engine: OpenTiledMultiviewEngine
) : MultiviewPlaybackBackend {
    override val status = MultiviewBackendStatus(
        kind = MultiviewBackendKind.OPEN_TME,
        available = true,
        singlePlayer = true,
        reason = "Open tiled backend using one Media3 player for a genuine single-source mosaic"
    )

    override fun canHandle(session: TiledMultiviewSession): Boolean =
        session.feeds.size > 1 &&
            session.tileWidth != null && session.tileWidth > 0 &&
            session.tileHeight != null && session.tileHeight > 0 &&
            session.feeds.mapNotNull { it.url?.takeIf(String::isNotBlank) }.distinct().size == 1

    fun canHandle(tme: TmePlayback, source: StreamSource): Boolean =
        tme.topology == TmeTopology.SINGLE_MOSAIC_SOURCE &&
            engine.canHandle(tme)

    fun requiresSecureOutput(source: StreamSource): Boolean =
        source.drmLicenseUrl != null

    fun prepare(tme: TmePlayback, source: StreamSource, referenceFeedId: String? = null): Boolean =
        engine.prepare(tme, source, referenceFeedId)
}
