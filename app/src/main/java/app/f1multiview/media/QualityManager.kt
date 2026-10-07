package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import app.f1multiview.core.playback.Quality
import kotlin.math.abs

/**
 * Central video-quality policy for multiview.
 *
 * Explicit user quality is respected when a compatible rendition exists.
 * AUTO adapts to the decoder/resource budget. The reference feed is allowed a
 * larger budget than secondary feeds.
 */
class QualityManager {
    data class Budget(val maxWidth: Int, val maxHeight: Int)

    fun autoBudget(isReference: Boolean, activeDecoderCount: Int, capacity: Int): Budget =
        if (isReference) Budget(Int.MAX_VALUE, Int.MAX_VALUE)
        else if (activeDecoderCount >= capacity) Budget(640, 360)
        else Budget(854, 480)

    fun qualityAvailable(tracks: Tracks, quality: Quality): Boolean {
        if (quality == Quality.AUTO) return true
        return videoFormats(tracks).any { it.height >= minimumHeight(quality) }
    }

    fun availableResolutions(tracks: Tracks): List<Pair<Int, Int>> =
        videoFormats(tracks)
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.second }

    fun parameters(
        player: ExoPlayer,
        quality: Quality,
        isReference: Boolean,
        autoBudget: Budget
    ): TrackSelectionParameters {
        val builder = player.trackSelectionParameters.buildUpon()
        when (quality) {
            Quality.AUTO -> {
                builder.setMinVideoSize(0, 0)
                    .setMaxVideoSize(autoBudget.maxWidth, autoBudget.maxHeight)
                    .setForceHighestSupportedBitrate(false)
            }
            Quality.UHD, Quality.FHD, Quality.HD, Quality.SD -> {
                val target = targetResolution(
                    player,
                    minimumHeight(quality),
                    fallbackWidth(quality),
                    minimumHeight(quality)
                )
                builder.setMinVideoSize(target.first, target.second)
                    .setMaxVideoSize(target.first, target.second)
                    .setForceHighestSupportedBitrate(false)
            }
        }
        return builder.build()
    }

    fun chooseAutoTrack(
        tracks: Tracks,
        budget: Budget
    ): Triple<Tracks.Group, Int, Format>? {
        val candidates = tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).map { index ->
                    Triple(group, index, group.getTrackFormat(index))
                }
            }
            .filter { (_, _, format) ->
                format.width > 0 && format.height > 0 &&
                    format.height <= budget.maxHeight && format.width <= budget.maxWidth
            }
            .filter { (group, index, _) ->
                val support = group.getTrackSupport(index)
                support == C.FORMAT_HANDLED || support == C.FORMAT_EXCEEDS_CAPABILITIES
            }

        return candidates
            .sortedWith(
                compareByDescending<Triple<Tracks.Group, Int, Format>> {
                    it.third.sampleMimeType.equals(MimeTypes.VIDEO_H264, true)
                }.thenByDescending { it.third.height }
                    .thenByDescending { it.third.width }
                    .thenByDescending { it.third.bitrate }
            )
            .firstOrNull()
    }

    fun targetResolution(
        player: ExoPlayer,
        desiredHeight: Int,
        fallbackWidth: Int,
        fallbackHeight: Int
    ): Pair<Int, Int> {
        val formats = videoFormats(player.currentTracks)
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.width to it.height }
        if (formats.isEmpty()) return fallbackWidth to fallbackHeight

        val candidates = formats.filter {
            it.height >= desiredHeight - 32 && it.height <= desiredHeight + 256
        }
        val best = candidates.maxWithOrNull(
            compareBy<Format> { it.height }.thenBy { it.width }
        ) ?: formats.minByOrNull { abs(it.height - desiredHeight) }

        return best?.let { it.width to it.height } ?: (fallbackWidth to fallbackHeight)
    }

    fun minimumHeight(quality: Quality): Int = when (quality) {
        Quality.AUTO -> 0
        Quality.UHD -> 2160
        Quality.FHD -> 1080
        Quality.HD -> 720
        Quality.SD -> 480
    }

    private fun fallbackWidth(quality: Quality): Int = when (quality) {
        Quality.UHD -> 3840
        Quality.FHD -> 1920
        Quality.HD -> 1280
        Quality.SD -> 854
        Quality.AUTO -> 0
    }

    private fun videoFormats(tracks: Tracks): List<Format> =
        tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).map { group.getTrackFormat(it) }
            }
}
