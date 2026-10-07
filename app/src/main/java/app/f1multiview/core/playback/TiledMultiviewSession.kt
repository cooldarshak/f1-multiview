package app.f1multiview.core.playback

import app.f1multiview.data.f1tv.TmePlayback
import app.f1multiview.data.f1tv.TmePlaybackParser

/**
 * Typed representation of F1's TME multiview contract.
 *
 * This is intentionally independent from Media3/ExoPlayer. A logical TME feed is
 * not a decoder and must never cause the UI to allocate a player by itself.
 */
data class TiledMultiviewFeed(
    val index: Int,
    val channelId: Int?,
    val encoderId: String?,
    val url: String?,
    val uuid: String?,
    val audioEnglish: String?,
    val audioSpanish: String?,
    val subtitleEnglish: String?,
    val subtitleSpanish: String?
)

data class TiledMultiviewSession(
    val version: Int?,
    val channel: String?,
    val contentId: Int?,
    val tileWidth: Int?,
    val tileHeight: Int?,
    val feeds: List<TiledMultiviewFeed>
) {
    val feedIds: List<String>
        get() = feeds.mapIndexed { index, feed ->
            feed.channelId?.toString() ?: feed.encoderId ?: feed.uuid ?: "feed-$index"
        }

    val hasTileGeometry: Boolean
        get() = tileWidth != null && tileHeight != null && tileWidth > 0 && tileHeight > 0

    val isUsable: Boolean
        get() = feeds.isNotEmpty() && hasTileGeometry
}

object TiledMultiviewSessionParser {
    fun parse(json: String): TiledMultiviewSession? =
        TmePlaybackParser.parse(json)?.toModel()

    private fun TmePlayback.toModel(): TiledMultiviewSession =
        TiledMultiviewSession(
            version = version,
            channel = channel,
            contentId = contentId,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            feeds = feeds.mapIndexed { index, feed ->
                TiledMultiviewFeed(
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
}
