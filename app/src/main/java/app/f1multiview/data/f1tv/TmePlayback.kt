package app.f1multiview.data.f1tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * F1's TME payload as reconstructed from the production application's bytecode.
 *
 * This deliberately models only fields that were confirmed in the APK:
 * Tme.version, Tme.channel, Tme.metadata.contentId,
 * Tme.advanced.tileSize.width/height, and Tme.feeds[*].
 */
enum class TmeTopology {
    /**
     * One media URL represents the complete tiled/mosaic representation.
     * This is the only topology that can legitimately enter our one-player path.
     */
    SINGLE_MOSAIC_SOURCE,

    /**
     * Each logical feed has its own compressed media URL. This requires multiple
     * decoders unless a provider-specific tiled decoder exists.
     */
    INDEPENDENT_FEED_SOURCES,

    /**
     * TME metadata is incomplete and cannot safely determine the media topology.
     */
    UNKNOWN
}

data class TmePlayback(
    val version: Int?,
    val channel: String?,
    val contentId: Int?,
    val tileWidth: Int?,
    val tileHeight: Int?,
    val feeds: List<TmeFeed>
) {
    val topology: TmeTopology
        get() {
            val urls = feeds.mapNotNull { it.url?.trim()?.takeIf(String::isNotBlank) }.distinct()
            if (urls.isEmpty()) return TmeTopology.UNKNOWN
            if (urls.size == 1 && tileWidth != null && tileHeight != null && feeds.size > 1) {
                return TmeTopology.SINGLE_MOSAIC_SOURCE
            }
            return TmeTopology.INDEPENDENT_FEED_SOURCES
        }

    val isTiledSource: Boolean
        get() = topology == TmeTopology.SINGLE_MOSAIC_SOURCE
}

data class TmeFeed(
    val audioEnglish: String?,
    val audioSpanish: String?,
    val channelId: Int?,
    val encoderId: String?,
    val subtitleEnglish: String?,
    val subtitleSpanish: String?,
    val url: String?,
    val uuid: String?
)

object TmePlaybackParser {
    fun parse(json: String): TmePlayback? = runCatching {
        val root = JSONObject(json)
        val metadata = root.optJSONObject("metadata")
        val advanced = root.optJSONObject("advanced")
        val tileSize = advanced?.optJSONObject("tileSize")

        val feeds = buildList {
            val array: JSONArray = root.optJSONArray("feeds") ?: JSONArray()
            for (index in 0 until array.length()) {
                val feed = array.optJSONObject(index) ?: continue
                val audio = feed.optJSONObject("audioTrackNames")
                val feedMetadata = feed.optJSONObject("metadata")
                val subtitles = feed.optJSONObject("subtitleTrackNames")
                add(
                    TmeFeed(
                        audioEnglish = audio?.optString("eng").takeUnless { it.isNullOrBlank() },
                        audioSpanish = audio?.optString("spa").takeUnless(String?::isNullOrBlank),
                        channelId = feedMetadata?.optInt("channelId", -1)?.takeIf { it >= 0 },
                        encoderId = feed.optString("encoderId").takeUnless(String::isNullOrBlank),
                        subtitleEnglish = subtitles?.optString("engSubtitle").takeUnless(String?::isNullOrBlank),
                        subtitleSpanish = subtitles?.optString("spaSubtitle").takeUnless(String?::isNullOrBlank),
                        url = feed.optString("url").takeUnless(String::isNullOrBlank),
                        uuid = feed.optString("uuid").takeUnless(String::isNullOrBlank)
                    )
                )
            }
        }

        TmePlayback(
            version = root.optInt("version", -1).takeIf { it >= 0 },
            channel = root.optString("channel").takeUnless(String::isNullOrBlank),
            contentId = metadata?.optInt("contentId", -1)?.takeIf { it >= 0 },
            tileWidth = tileSize?.optInt("width", -1)?.takeIf { it > 0 },
            tileHeight = tileSize?.optInt("height", -1)?.takeIf { it > 0 },
            feeds = feeds
        )
    }.getOrNull()
}
