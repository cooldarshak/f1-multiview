package app.f1multiview.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import java.io.ByteArrayInputStream

/**
 * Resolves a bounded first CMAF media request from an already-authorized DASH manifest.
 *
 * This deliberately supports only representations whose segment index is declared in the MPD
 * (for example SegmentTemplate/SegmentList). External SIDX-only representations are rejected until
 * their index-fetch/parse lifecycle is implemented. The caller must fetch the returned DataSpecs
 * through the authorized, role-aware DataSource and must not treat a plan as proof of playback.
 */
@OptIn(UnstableApi::class)
internal object AuthorizedDashManifestResolver {
    data class SegmentRequest(
        val dataSpec: DataSpec,
        val presentationTimeUs: Long,
        val durationUs: Long
    )

    data class VideoPlan(
        val format: Format,
        val initialization: DataSpec,
        val firstMediaSegment: SegmentRequest,
        val periodIndex: Int,
        val dynamicManifest: Boolean,
        val manifestDeclaredDrmInitData: Boolean
    )

    fun resolve(
        manifestUri: Uri,
        manifestBytes: ByteArray,
        requestHeaders: Map<String, String>,
        maxWidth: Int = Int.MAX_VALUE,
        maxHeight: Int = Int.MAX_VALUE,
        nowUnixTimeUs: Long = System.currentTimeMillis() * 1_000L
    ): VideoPlan {
        require(manifestBytes.isNotEmpty()) { "DASH manifest is empty" }
        require(maxWidth > 0 && maxHeight > 0) { "Maximum video dimensions must be positive" }

        val manifest = DashManifestParser().parse(manifestUri, ByteArrayInputStream(manifestBytes))
        require(manifest.periodCount > 0) { "DASH manifest contains no periods" }

        for (periodIndex in 0 until manifest.periodCount) {
            val period = manifest.getPeriod(periodIndex)
            // A period may expose multiple video AdaptationSets (for example alternate
            // camera/role representations). Catalogue all of them, and prefer a representation
            // carrying actual Widevine init data rather than blindly taking the first set.
            val candidates = period.adaptationSets
                .filter { it.type == C.TRACK_TYPE_VIDEO }
                .flatMap { it.representations }
                .filter { representation ->
                    val mime = representation.format.sampleMimeType
                    mime?.startsWith("video/", ignoreCase = true) == true &&
                        representation.getIndex() != null &&
                        representation.getInitializationUri() != null
                }
            if (candidates.isEmpty()) continue

            val protectedCandidates = candidates.filter { representation ->
                val drm = representation.format.drmInitData
                drm != null && (0 until drm.schemeDataCount).any { index ->
                    drm.get(index).matches(C.WIDEVINE_UUID)
                }
            }
            val selectionPool = protectedCandidates.ifEmpty { candidates }
            val withinBounds = selectionPool.filter { representation ->
                representation.format.width in 1..maxWidth &&
                    representation.format.height in 1..maxHeight
            }
            val selected = if (withinBounds.isNotEmpty()) {
                withinBounds.maxByOrNull { pixelArea(it.format) }
            } else {
                selectionPool.minByOrNull { pixelArea(it.format) }
            } ?: continue

            val index = requireNotNull(selected.getIndex())
            val periodDurationUs = manifest.getPeriodDurationUs(periodIndex)
            val segmentNumber = if (manifest.dynamic) {
                val availableCount = index.getAvailableSegmentCount(periodDurationUs, nowUnixTimeUs)
                check(availableCount != 0L) { "DASH video representation has no currently available media segments" }
                index.getFirstAvailableSegmentNum(periodDurationUs, nowUnixTimeUs)
            } else {
                check(index.getSegmentCount(periodDurationUs) != 0L) {
                    "DASH video representation has no media segments"
                }
                index.getFirstSegmentNum()
            }

            val baseUrl = selected.baseUrls.firstOrNull()?.url
                ?: error("DASH video representation has no resolved base URL")
            val initRange = requireNotNull(selected.getInitializationUri())
            val mediaRange = index.getSegmentUrl(segmentNumber)

            return VideoPlan(
                format = selected.format,
                initialization = dataSpec(initRange, baseUrl, requestHeaders, mediaSegment = false),
                firstMediaSegment = SegmentRequest(
                    dataSpec = dataSpec(mediaRange, baseUrl, requestHeaders, mediaSegment = true),
                    presentationTimeUs = index.getTimeUs(segmentNumber),
                    durationUs = index.getDurationUs(segmentNumber, periodDurationUs)
                ),
                periodIndex = periodIndex,
                dynamicManifest = manifest.dynamic,
                manifestDeclaredDrmInitData = selected.format.drmInitData != null
            )
        }

        error(
            "No supported DASH video representation with both an MPD-backed segment index and " +
                "initialization URI; external-index-only and non-CMAF paths remain unsupported"
        )
    }

    private fun dataSpec(
        range: androidx.media3.exoplayer.dash.manifest.RangedUri,
        baseUrl: String,
        requestHeaders: Map<String, String>,
        mediaSegment: Boolean
    ): DataSpec = DataSpec.Builder()
        .setUri(range.resolveUri(baseUrl))
        .setPosition(range.start)
        .setLength(range.length)
        .setHttpRequestHeaders(requestHeaders)
        .apply {
            if (mediaSegment) setCustomData(MediaSegmentRoleDataSource.MEDIA_SEGMENT_ROLE)
        }
        .build()

    private fun pixelArea(format: Format): Long =
        maxOf(1, format.width).toLong() * maxOf(1, format.height).toLong()
}
