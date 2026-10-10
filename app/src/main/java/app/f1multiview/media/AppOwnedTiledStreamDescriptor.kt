package app.f1multiview.media

import java.net.URI

/**
 * App-owned contract for a single coded video elementary stream containing multiple HEVC tiles.
 *
 * This is deliberately not a multi-player/decoder descriptor. The stream is assembled/encoded
 * upstream or supplied as a provider-authored tiled representation; the Android client must never
 * concatenate independently encoded feeds or decrypt protected samples to manufacture this input.
 *
 * This contract validates the boundary only. It does not claim to parse HEVC tile syntax, rewrite
 * NAL units, acquire Widevine keys, or prove device playback.
 */
internal data class AppOwnedTiledStreamDescriptor(
    val streamUri: String,
    val codec: Codec,
    val drmScheme: DrmScheme,
    val licenseUri: String,
    val origin: Origin,
    val presentationTimelineId: String,
    val timescale: Long,
    val tiles: List<Tile>,
    val keyRotationSignalled: Boolean
) {
    enum class Codec { HEVC_TILES }
    enum class DrmScheme { WIDEVINE_CENC }
    enum class Origin { PROVIDER_AUTHORIZED_TILED_STREAM, AUTHORIZED_SERVER_SIDE_PACKAGER }

    data class Tile(
        val sourceId: String,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double
    )

    fun validate(): Validation {
        val errors = mutableListOf<String>()
        if (!isHttps(streamUri)) errors += "streamUri must be an HTTPS URL"
        if (!isHttps(licenseUri)) errors += "licenseUri must be an HTTPS URL"
        if (presentationTimelineId.isBlank()) errors += "presentationTimelineId is required"
        if (timescale <= 0) errors += "timescale must be positive"
        if (tiles.size !in 2..4) errors += "tile count must be 2, 3, or 4"
        if (tiles.any { it.sourceId.isBlank() }) errors += "every tile needs a source id"
        if (tiles.map { it.sourceId }.distinct().size != tiles.size) errors += "tile source ids must be unique"
        tiles.forEachIndexed { index, tile ->
            if (!tile.x.isFinite() || !tile.y.isFinite() ||
                !tile.width.isFinite() || !tile.height.isFinite()) {
                errors += "tile[$index] geometry must be finite"
            } else {
                if (tile.x < 0.0 || tile.y < 0.0 || tile.width <= 0.0 || tile.height <= 0.0 ||
                    tile.x + tile.width > 1.0 || tile.y + tile.height > 1.0) {
                    errors += "tile[$index] must fit inside normalized stream bounds"
                }
            }
        }
        for (i in tiles.indices) for (j in i + 1 until tiles.size) {
            if (overlaps(tiles[i], tiles[j])) errors += "tile[$i] overlaps tile[$j]"
        }
        if (codec != Codec.HEVC_TILES) errors += "single-decoder tiled input requires a supported HEVC tiled bitstream"
        if (drmScheme != DrmScheme.WIDEVINE_CENC) errors += "authorized Widevine CENC is required"
        return Validation(errors.distinct())
    }

    data class Validation(val errors: List<String>) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    private fun isHttps(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    private fun overlaps(a: Tile, b: Tile): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width &&
            a.y < b.y + b.height && b.y < a.y + a.height
}
