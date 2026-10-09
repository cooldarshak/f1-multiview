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
    val ppsTilesEnabled: Boolean? = null,
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
    HEVC_PPS_TILES_DISABLED,
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

        val noTilePps = evidence.filter { it.ppsTilesEnabled == false }
        if (noTilePps.isNotEmpty()) {
            return report(
                TmeCmafPreflightStatus.HEVC_PPS_TILES_DISABLED,
                "HEVC PPS explicitly disables spatial tiles for: ${noTilePps.joinToString { it.feedId }}. These streams are not compatible with the spatial tile merger."
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


/**
 * Reads only the HEVC PPS syntax needed to determine tiles_enabled_flag.
 * This is a necessary signal for spatial-tile merging, not sufficient proof that
 * different URLs contain tile slices from the same picture.
 */
internal object HevcPpsTileInspector {
    fun tilesEnabled(codecConfig: ByteArray): Boolean? {
        val nals = when {
            codecConfig.size >= 23 && codecConfig[0].toInt() == 1 -> parseHvcc(codecConfig)
            else -> splitAnnexB(codecConfig)
        }
        val pps = nals.firstOrNull { nalType(it) == 34 } ?: return null
        if (pps.size <= 2) return null
        val rbsp = removeEmulationPrevention(pps.copyOfRange(2, pps.size))
        return runCatching {
            val bits = BitReader(rbsp)
            bits.readUe() // pps_pic_parameter_set_id
            bits.readUe() // pps_seq_parameter_set_id
            bits.readBit() // dependent_slice_segments_enabled_flag
            bits.readBit() // output_flag_present_flag
            bits.readBits(3) // num_extra_slice_header_bits
            bits.readBit() // sign_data_hiding_enabled_flag
            bits.readBit() // cabac_init_present_flag
            bits.readUe() // num_ref_idx_l0_default_active_minus1
            bits.readUe() // num_ref_idx_l1_default_active_minus1
            bits.readSe() // init_qp_minus26
            bits.readBit() // constrained_intra_pred_flag
            bits.readBit() // transform_skip_enabled_flag
            val cuQpDeltaEnabled = bits.readBit()
            if (cuQpDeltaEnabled) bits.readUe()
            bits.readSe() // pps_cb_qp_offset
            bits.readSe() // pps_cr_qp_offset
            bits.readBit() // pps_slice_chroma_qp_offsets_present_flag
            bits.readBit() // weighted_pred_flag
            bits.readBit() // weighted_bipred_flag
            bits.readBit() // transquant_bypass_enabled_flag
            bits.readBit() // tiles_enabled_flag
        }.getOrNull()
    }

    private fun parseHvcc(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var offset = 23
        val arrays = data[22].toInt() and 0xff
        repeat(arrays) {
            if (offset + 3 > data.size) return nals
            offset++ // array completeness + NAL unit type
            val count = ((data[offset].toInt() and 0xff) shl 8) or
                (data[offset + 1].toInt() and 0xff)
            offset += 2
            repeat(count) {
                if (offset + 2 > data.size) return nals
                val size = ((data[offset].toInt() and 0xff) shl 8) or
                    (data[offset + 1].toInt() and 0xff)
                offset += 2
                if (size <= 0 || offset + size > data.size) return nals
                nals += data.copyOfRange(offset, offset + size)
                offset += size
            }
        }
        return nals
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        fun startCodeAt(i: Int): Int = when {
            i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
            i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte() -> 3
            else -> 0
        }
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < data.size) {
            val length = startCodeAt(i)
            if (length > 0) {
                starts += i to length
                i += length
            } else {
                i++
            }
        }
        return starts.mapIndexedNotNull { index, (start, prefix) ->
            val from = start + prefix
            val until = starts.getOrNull(index + 1)?.first ?: data.size
            if (until > from) data.copyOfRange(from, until) else null
        }
    }

    private fun nalType(nal: ByteArray): Int? =
        if (nal.size < 2) null else (nal[0].toInt() ushr 1) and 0x3f

    private fun removeEmulationPrevention(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size)
        var zeroCount = 0
        data.forEach { value ->
            val byte = value.toInt() and 0xff
            if (zeroCount >= 2 && byte == 3) {
                zeroCount = 0
            } else {
                out += value
                zeroCount = if (byte == 0) zeroCount + 1 else 0
            }
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray) {
        private var bitOffset = 0

        fun readBit(): Boolean {
            check(bitOffset < data.size * 8) { "Truncated HEVC PPS" }
            val value = (data[bitOffset / 8].toInt() ushr (7 - bitOffset % 8)) and 1
            bitOffset++
            return value != 0
        }

        fun readBits(count: Int): Int {
            var result = 0
            repeat(count) { result = (result shl 1) or if (readBit()) 1 else 0 }
            return result
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (!readBit()) {
                leadingZeros++
                check(leadingZeros <= 30) { "Invalid HEVC Exp-Golomb value" }
            }
            if (leadingZeros == 0) return 0
            return ((1 shl leadingZeros) - 1) + readBits(leadingZeros)
        }

        fun readSe(): Int {
            val codeNum = readUe()
            return if (codeNum % 2 == 0) -(codeNum / 2) else (codeNum + 1) / 2
        }
    }
}
