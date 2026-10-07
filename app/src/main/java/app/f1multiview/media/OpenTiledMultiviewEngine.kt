package app.f1multiview.media

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.f1multiview.core.playback.TiledMultiviewSession
import app.f1multiview.data.f1tv.TmePlayback
import app.f1multiview.data.f1tv.TmeTopology
import app.f1multiview.model.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

/**
 * Open tiled/mosaic playback engine.
 *
 * This is intentionally independent of Tiledmedia. It implements the portion of
 * a tiled-player contract that can be built from public Android/Media3 APIs:
 *
 *  - one ExoPlayer owns one tiled/mosaic media source;
 *  - logical feeds are metadata/tile selections, not decoder instances;
 *  - playback clock and ABR belong to the single player;
 *  - tile selection/layout is independent from playback lifecycle.
 *
 * It does NOT turn independent feed URLs into one decoder. Such a source is
 * rejected by canHandle(), because doing otherwise would be technically false.
 *
 * Protected F1 content remains on Media3's secure SurfaceView path.
 */
class OpenTiledMultiviewEngine(
    private val playerFactory: (StreamSource) -> ExoPlayer
) {
    data class Tile(
        val feedId: String,
        val index: Int,
        val row: Int,
        val column: Int,
        val widthPx: Int,
        val heightPx: Int
    )

    data class State(
        val active: Boolean = false,
        val sourceUrl: String? = null,
        val tiles: List<Tile> = emptyList(),
        val selectedFeedIds: List<String> = emptyList(),
        val referenceFeedId: String? = null,
        val positionMs: Long = 0L,
        val isPlaying: Boolean = false,
        val driftMs: Long = 0L
    )

    private var player: ExoPlayer? = null
    private var session: TiledMultiviewSession? = null
    private var source: StreamSource? = null
    private var referenceFeedId: String? = null\n    private var decoderPlan: OpenTiledDecoderPlan? = null\n    private var sourceVideoWidth = 0\n    private var sourceVideoHeight = 0
    private val qualityManager = QualityManager()
    private val _state = MutableStateFlow(State())
    private var compositorPlayerListener: Player.Listener? = null
    val state: StateFlow<State> = _state.asStateFlow()

    fun canHandle(tme: TmePlayback): Boolean =
        tme.topology == TmeTopology.SINGLE_MOSAIC_SOURCE &&
            !tme.feeds.firstOrNull()?.url.isNullOrBlank() &&
            tme.tileWidth != null &&
            tme.tileHeight != null

    @OptIn(UnstableApi::class)
    fun prepare(
        tme: TmePlayback,
        source: StreamSource,
        referenceFeedId: String? = null
    ): Boolean {
        if (!canHandle(tme)) return false
        val url = tme.feeds.firstNotNullOfOrNull { it.url?.takeIf(String::isNotBlank) }
            ?: return false

        if (player != null && _state.value.sourceUrl == url) {
            val ids = tme.feeds.mapIndexed { index, feed ->
                feed.uuid ?: feed.channelId?.toString() ?: "feed-$index"
            }
            publish(selected = ids)
            return true
        }

        release()
        this.source = source
        this.referenceFeedId = referenceFeedId ?: tme.feeds.firstOrNull()?.uuid
        this.player = playerFactory(source).also { p ->
            val builder = MediaItem.Builder()
                .setUri(url)
                .setMediaId("tme-mosaic-" + (tme.contentId ?: "unknown"))
            if (source.drmLicenseUrl != null) {
                val drmBuilder = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(source.drmLicenseUrl)
                if (source.drmRequestHeaders.isNotEmpty()) {
                    drmBuilder.setLicenseRequestHeaders(source.drmRequestHeaders)
                }
                builder.setDrmConfiguration(drmBuilder.build())
            }
            p.setMediaItem(builder.build())
            p.playWhenReady = true
            p.prepare()
        }

        val columns = max(1, kotlin.math.ceil(kotlin.math.sqrt(tme.feeds.size.toDouble())).toInt())
        val rows = max(1, kotlin.math.ceil(tme.feeds.size.toDouble() / columns).toInt())
        val tileWidth = tme.tileWidth ?: return false
        val tileHeight = tme.tileHeight ?: return false

        val tiles = tme.feeds.mapIndexed { index, feed ->
            Tile(
                feedId = feed.uuid ?: feed.channelId?.toString() ?: "feed-$index",
                index = index,
                row = index / columns,
                column = index % columns,
                widthPx = tileWidth,
                heightPx = tileHeight
            )
        }

        session = TiledMultiviewSession(
            version = tme.version ?: 0,
            channel = tme.channel.orEmpty(),
            contentId = tme.contentId ?: 0,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            feeds = tme.feeds.mapIndexed { index, feed ->
                app.f1multiview.core.playback.TiledMultiviewFeed(
                    index = index,
                    channelId = feed.channelId,
                    encoderId = feed.encoderId,
                    url = feed.url,
                    uuid = feed.uuid,
                    audioEnglish = feed.audioEnglish,
                    audioSpanish = feed.audioSpanish,
                    subtitleEnglish = feed.subtitleEnglish,
                    subtitleSpanish = feed.subtitleSpanish
                )
            }
        )

        publish(tiles = tiles, selected = tiles.map { it.feedId })
        return true
    }

    fun selectFeeds(feedIds: List<String>) {
        val current = _state.value.tiles
        val selected = feedIds.filter { id -> current.any { it.feedId == id } }.distinct()
        publish(selected = selected)
    }

    fun selectAll() {
        publish(selected = _state.value.tiles.map { it.feedId })
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 2.0f))
    }

    /**
     * Shared-clock correction for callers that compare an external reference.
     * Small errors use rate correction. Large errors use a seek.
     */
    fun correctClock(referencePositionMs: Long, nowMs: Long = SystemClock.elapsedRealtime()): Long {
        val p = player ?: return 0L
        val drift = referencePositionMs - p.currentPosition
        val absDrift = abs(drift)
        when {
            absDrift >= 250L -> {
                p.seekTo(referencePositionMs.coerceAtLeast(0L))
                p.playbackParameters = PlaybackParameters(1f)
            }
            absDrift >= 35L -> {
                val correction = (drift / 1200f).coerceIn(-0.04f, 0.04f)
                p.playbackParameters = PlaybackParameters(1f + correction)
            }
            else -> {
                p.playbackParameters = PlaybackParameters(1f)
            }
        }
        publish(drift = drift)
        return nowMs
    }


    fun setQuality(quality: app.f1multiview.core.playback.Quality) {
        val p = player ?: return
        p.trackSelectionParameters = qualityManager.parameters(
            p,
            quality,
            qualityManager.autoBudget(isReference = true, activeDecoderCount = 1, capacity = 1)
        )
    }

    fun availableVideoResolutions(): List<Pair<Int, Int>> =
        player?.let(qualityManager::availableResolutions) ?: emptyList()

    fun selectAudioLanguage(language: String?) {
        player?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(language)
                .build()
        }
    }

    fun selectSubtitleLanguage(language: String?) {
        player?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(language)
                .build()
        }
    }

    fun attachListener(listener: Player.Listener): Boolean {
        player?.addListener(listener) ?: return false
        return true
    }

    fun detachListener(listener: Player.Listener) {
        player?.removeListener(listener)
    }

    fun player(): ExoPlayer? = player

    fun attachTo(view: OpenTiledCompositorView) {
        view.setSession(session ?: return)
        view.setSelectedFeedIds(_state.value.selectedFeedIds)

        compositorPlayerListener?.let { listener -> player?.removeListener(listener) }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                sourceVideoWidth = videoSize.width\n                sourceVideoHeight = videoSize.height\n                decoderPlan = session?.let { OpenTiledDecoderPlan.from(it, videoSize.width, videoSize.height) }\n                view.setSourceVideoSize(videoSize.width, videoSize.height)
            }
        }
        compositorPlayerListener = listener
        player?.addListener(listener)

        view.setListener(object : OpenTiledCompositorView.Listener {
            override fun onOutputSurfaceReady(surface: android.view.Surface) {
                val p = player ?: return
                sourceVideoWidth = p.videoSize.width\n                sourceVideoHeight = p.videoSize.height\n                decoderPlan = session?.let { OpenTiledDecoderPlan.from(it, p.videoSize.width, p.videoSize.height) }\n                view.setSourceVideoSize(p.videoSize.width, p.videoSize.height)
                Handler(p.applicationLooper).post { p.setVideoSurface(surface) }
            }

            override fun onOutputSurfaceReleased() {
                player?.let { p ->
                    Handler(p.applicationLooper).post { p.clearVideoSurface() }
                }
            }
        })
    }

    fun selectVisibleFeeds(feedIds: List<String>) {
        selectFeeds(feedIds)
    }


    fun attachSecureTo(view: OpenTiledSecureSurfaceView) {
        view.setSession(session ?: return)
        view.setListener(object : OpenTiledSecureSurfaceView.Listener {
            override fun onSurfaceReady(surface: android.view.Surface) {
                val p = player ?: return
                android.os.Handler(p.applicationLooper).post { p.setVideoSurface(surface) }
            }

            override fun onSurfaceReleased() {
                player?.let { p ->
                    android.os.Handler(p.applicationLooper).post { p.clearVideoSurface() }
                }
            }
        })
    }

    fun release() {
        compositorPlayerListener?.let { listener -> player?.removeListener(listener) }
        compositorPlayerListener = null
        player?.release()
        player = null
        session = null
        source = null
        referenceFeedId = null
        _state.value = State()
    }

    private fun publish(
        tiles: List<Tile> = _state.value.tiles,
        selected: List<String> = _state.value.selectedFeedIds,
        drift: Long = _state.value.driftMs
    ) {
        val p = player
        _state.value = State(
            active = p != null,
            sourceUrl = p?.currentMediaItem?.localConfiguration?.uri?.toString(),
            tiles = tiles,
            selectedFeedIds = selected,
            referenceFeedId = referenceFeedId,
            positionMs = p?.currentPosition ?: 0L,
            isPlaying = p?.isPlaying == true,
            driftMs = drift
        )
    }
}
