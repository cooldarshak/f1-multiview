package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewSession
import app.f1multiview.data.f1tv.TmeTopology

/**
 * Evidence collected from CMAF init/fragment metadata without decrypting or copying
 * protected sample payloads. This is a diagnostic contract, not proof that streams
 * can be merged into one decoder.
 */
data class TmeCmafFeedEvidence(
    val feedId: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val codecConfigFingerprint: String?,
    val encrypted: Boolean,
    val sampleCount: Int,
    val firstSampleTimeUs: Long?,
    val firstSampleIsSync: Boolean?,
    val encryptionMethod: String? = null,
    val inspectionError: String? = null
)

enum class TmeCmafPreflightStatus {
    SINGLE_MOSAIC_SOURCE,
    INCOMPLETE_EVIDENCE,
    INSPECTION_FAILED,
    DRM_PROTECTED_REQUIRES_SECURE_PIPELINE,
    NON_HEVC_INPUT,
    TILE_GEOMETRY_MISMATCH,
    CODEC_CONFIG_MISMATCH,
    TIMELINE_NOT_ALIGNED,
    METADATA_DOES_NOT_PROVE_TILE_GRID,
    CANDIDATE_REQUIRES_SPATIAL_BITSTREAM_PROOF
}

data class TmeCmafPreflightReport(
    val status: TmeCmafPreflightStatus,
    val nativeMergeEligible: Boolean,
    val summary: String,
    val evidence: List<TmeCmafFeedEvidence>
)

/**
 * Conservative assessment for the own-source native merger.
 *
 * Even a successful metadata/timeline check does NOT prove that each input contains
 * compatible spatial HEVC tile slices from the same coded picture. That requires
 * bitstream-level validation and a successful merger fixture. Encrypted samples are
 * never decrypted or sent through the clear-content merger by this assessor.
 */
object TmeCmafPreflight {
    private const val MAX_FIRST_SAMPLE_SKEW_US = 50_000L

    fun assess(
        session: TiledMultiviewSession,
        evidence: List<TmeCmafFeedEvidence>
    ): TmeCmafPreflightReport {
        fun report(status: TmeCmafPreflightStatus, summary: String) =
            TmeCmafPreflightReport(status, nativeMergeEligible = false, summary, evidence)

        if (session.topology == TmeTopology.SINGLE_MOSAIC_SOURCE) {
            return report(
                TmeCmafPreflightStatus.SINGLE_MOSAIC_SOURCE,
                "One shared source URL: use the existing single-player mosaic path; do not run the independent-feed merger."
            )
        }

        if (evidence.size != session.feeds.size || evidence.size < 2) {
            return report(
                TmeCmafPreflightStatus.INCOMPLETE_EVIDENCE,
                "Expected evidence for all ${session.feeds.size} feeds; received ${evidence.size}."
            )
        }

        val failed = evidence.filter { !it.inspectionError.isNullOrBlank() }
        if (failed.isNotEmpty()) {
            return report(
                TmeCmafPreflightStatus.INSPECTION_FAILED,
                "CMAF inspection failed for: ${failed.joinToString { it.feedId }}."
            )
        }

        val protected = evidence.filter { it.encrypted || !it.encryptionMethod.isNullOrBlank() }
        if (protected.isNotEmpty()) {
            return report(
                TmeCmafPreflightStatus.DRM_PROTECTED_REQUIRES_SECURE_PIPELINE,
                "Protected/encrypted input detected for: ${protected.joinToString { it.feedId }}. Clear-sample extraction and the current native merger are not eligible."
            )
        }

        val nonHevc = evidence.filter {
            !it.mimeType.orEmpty().equals("video/hevc", ignoreCase = true) &&
                !it.mimeType.orEmpty().equals("video/hev1", ignoreCase = true)
        }
        if (nonHevc.isNotEmpty()) {
            return report(
                TmeCmafPreflightStatus.NON_HEVC_INPUT,
                "Expected HEVC tracks; incompatible/unknown MIME on: ${nonHevc.joinToString { "${it.feedId}=${it.mimeType}" }}."
            )
        }

        val badGeometry = evidence.filter {
            it.width != session.tileWidth || it.height != session.tileHeight
        }
        if (badGeometry.isNotEmpty()) {
            return report(
                TmeCmafPreflightStatus.TILE_GEOMETRY_MISMATCH,
                "Track dimensions do not match declared tile size ${session.tileWidth}x${session.tileHeight}: ${badGeometry.joinToString { "${it.feedId}=${it.width}x${it.height}" }}."
            )
        }

        val fingerprints = evidence.mapNotNull { it.codecConfigFingerprint }.distinct()
        if (evidence.any { it.codecConfigFingerprint.isNullOrBlank() } || fingerprints.size != 1) {
            return report(
                TmeCmafPreflightStatus.CODEC_CONFIG_MISMATCH,
                "HEVC decoder configuration is missing or differs between feeds; do not configure hevcmerge."
            )
        }

        if (evidence.any { it.sampleCount <= 0 || it.firstSampleTimeUs == null || it.firstSampleIsSync == null }) {
            return report(
                TmeCmafPreflightStatus.INCOMPLETE_EVIDENCE,
                "At least one feed has no usable first-sample timing/keyframe evidence."
            )
        }

        val firstTimes = evidence.mapNotNull { it.firstSampleTimeUs }
        val skew = (firstTimes.maxOrNull() ?: 0L) - (firstTimes.minOrNull() ?: 0L)
        if (skew > MAX_FIRST_SAMPLE_SKEW_US) {
            return report(
                TmeCmafPreflightStatus.TIMELINE_NOT_ALIGNED,
                "First-sample PTS skew is ${skew}us, above the ${MAX_FIRST_SAMPLE_SKEW_US}us preflight tolerance."
            )
        }

        val allSync = evidence.all { it.firstSampleIsSync == true }
        if (!allSync) {
            return report(
                TmeCmafPreflightStatus.INCOMPLETE_EVIDENCE,
                "Not every feed begins at a sync sample; a shared random-access point is not established."
            )
        }

        if (session.topology != TmeTopology.MULTI_SOURCE_TILED_CANDIDATE) {
            return report(
                TmeCmafPreflightStatus.METADATA_DOES_NOT_PROVE_TILE_GRID,
                "Feed URLs and track metadata do not establish a complete, unique tile grid."
            )
        }

        return report(
            TmeCmafPreflightStatus.CANDIDATE_REQUIRES_SPATIAL_BITSTREAM_PROOF,
            "Metadata, clear HEVC configuration, dimensions and first-sample timing are consistent. This is still only a candidate: HEVC PPS/tile-slice structure and same-picture compatibility must be proven before enabling the merger."
        )
    }
}
