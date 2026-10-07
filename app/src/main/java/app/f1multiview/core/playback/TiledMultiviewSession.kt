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
    /**
     * Stable logical identity for a TME feed.
     *
     * UUID is preferred because channel IDs are provider-scoped and are not a
     * safe primary key when multiple TME payloads are present. The final
     * de-duplication step keeps the identity usable even when a malformed
     * payload repeats a UUID.
     */
    val feedIds: List<String>
        get() {
            val used = mutableMapOf<String, Int>()
            return feeds.mapIndexed { index, feed ->
                val base = feed.uuid?.takeIf { it.isNotBlank() }
                    ?: feed.channelId?.toString()
                    ?: feed.encoderId?.takeIf { it.isNotBlank() }
                    ?: "feed-$index"
                val occurrence = used.getOrDefault(base, 0)
                used[base] = occurrence + 1
                if (occurrence == 0) base else "$base#$occurrence"
            }
        }

    val hasTileGeometry: Boolean
        get() = tileWidth != null && tileHeight != null && tileWidth > 0 && tileHeight > 0

    val isUsable: Boolean
        get() = feeds.isNotEmpty() && hasTileGeometry
}

object TiledMultiviewSessionParser {
    fun parse(json: String): TiledMultiviewSession? =
        TmePlaybackParser.parse(json)?.toModel()

    fun TmePlayback.toModel(): TiledMultiviewSession =
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
