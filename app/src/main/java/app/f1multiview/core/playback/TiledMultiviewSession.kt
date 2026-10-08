package app.f1multiview.core.playback

import app.f1multiview.data.f1tv.TmeFeed
import app.f1multiview.data.f1tv.TmePlayback
import app.f1multiview.data.f1tv.TmePlaybackParser
import app.f1multiview.data.f1tv.TmeTopology

data class TiledMultiviewFeed(
    val index: Int,
    val channelId: Int?,
    val encoderId: String?,
    val url: String?,
    val uuid: String?,
    val audioEnglish: String?,
    val audioSpanish: String?,
    val subtitleEnglish: String?,
    val subtitleSpanish: String?,
    val tileIndex: Int? = null,
    val tileRow: Int? = null,
    val tileColumn: Int? = null
)

data class TiledMultiviewSession(
    val version: Int?,
    val channel: String?,
    val contentId: Int?,
    val tileWidth: Int?,
    val tileHeight: Int?,
    val tileCountHorizontal: Int? = null,
    val tileCountVertical: Int? = null,
    val feeds: List<TiledMultiviewFeed>
) {
    val feedIds: List<String>
        get() {
            val used = mutableMapOf<String, Int>()
            return feeds.mapIndexed { index, feed ->
                val base = feed.uuid?.takeIf(String::isNotBlank)
                    ?: feed.channelId?.toString()
                    ?: feed.encoderId?.takeIf(String::isNotBlank)
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

    val topology: TmeTopology
        get() {
            if (feeds.size <= 1) return TmeTopology.UNKNOWN
            val urls = feeds.map { it.url?.trim()?.takeIf(String::isNotBlank) }
            if (urls.any { it == null }) return TmeTopology.UNKNOWN
            val distinctUrls = urls.filterNotNull().distinct()
            if (distinctUrls.size == 1 && hasTileGeometry) return TmeTopology.SINGLE_MOSAIC_SOURCE
            return TmeTopology.INDEPENDENT_FEED_SOURCES
        }
}

object TiledMultiviewSessionParser {
    fun parse(json: String): TiledMultiviewSession? {
        return TmePlaybackParser.parse(json)?.toModel()
    }
}

fun TmePlayback.toModel(): TiledMultiviewSession {
    return TiledMultiviewSession(
        version = version,
        channel = channel,
        contentId = contentId,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        tileCountHorizontal = tileCountHorizontal,
        tileCountVertical = tileCountVertical,
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
                subtitleSpanish = feed.subtitleSpanish,
                tileIndex = feed.tileIndex,
                tileRow = feed.tileRow,
                tileColumn = feed.tileColumn
            )
        }
    )
}

fun TiledMultiviewSession.toPlayback(): TmePlayback {
    return TmePlayback(
        version = version,
        channel = channel,
        contentId = contentId,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        tileCountHorizontal = tileCountHorizontal,
        tileCountVertical = tileCountVertical,
        feeds = feeds.map { feed ->
            TmeFeed(
                audioEnglish = feed.audioEnglish,
                audioSpanish = feed.audioSpanish,
                channelId = feed.channelId,
                encoderId = feed.encoderId,
                subtitleEnglish = feed.subtitleEnglish,
                subtitleSpanish = feed.subtitleSpanish,
                url = feed.url,
                uuid = feed.uuid,
                tileIndex = feed.tileIndex,
                tileRow = feed.tileRow,
                tileColumn = feed.tileColumn
            )
        }
    )
}
