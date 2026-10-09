package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewFeed
import app.f1multiview.core.playback.TiledMultiviewSession
import app.f1multiview.data.f1tv.TmeTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmeCmafPreflightTest {
    private fun session() = TiledMultiviewSession(
        version = 1,
        channel = "F1",
        contentId = 7,
        tileWidth = 960,
        tileHeight = 540,
        tileCountHorizontal = 2,
        tileCountVertical = 1,
        feeds = listOf(
            TiledMultiviewFeed(0, 1, "world", "https://example/tile0.m3u8", "world", null, null, null, null, tileIndex = 0, tileRow = 0, tileColumn = 0),
            TiledMultiviewFeed(1, 2, "onboard", "https://example/tile1.m3u8", "onboard", null, null, null, null, tileIndex = 1, tileRow = 0, tileColumn = 1)
        )
    )

    private fun evidence(
        feedId: String,
        encrypted: Boolean = false,
        fingerprint: String = "same-hvcc",
        timeUs: Long = 1_000_000L,
        mime: String = "video/hevc",
        width: Int = 960,
        height: Int = 540,
        encryptionMethod: String? = null
    ) = TmeCmafFeedEvidence(
        feedId = feedId,
        mimeType = mime,
        width = width,
        height = height,
        codecConfigFingerprint = fingerprint,
        encrypted = encrypted,
        sampleCount = 30,
        firstSampleTimeUs = timeUs,
        firstSampleIsSync = true,
        encryptionMethod = encryptionMethod
    )

    @Test
    fun clearAlignedHevcIsOnlyACandidateUntilSpatialBitstreamIsProven() {
        val report = TmeCmafPreflight.assess(
            session(),
            listOf(evidence("world"), evidence("onboard", timeUs = 1_020_000L))
        )

        assertEquals(TmeTopology.MULTI_SOURCE_TILED_CANDIDATE, session().topology)
        assertEquals(TmeCmafPreflightStatus.CANDIDATE_REQUIRES_SPATIAL_BITSTREAM_PROOF, report.status)
        assertFalse(report.nativeMergeEligible)
        assertTrue(report.summary.contains("same-picture compatibility"))
    }

    @Test
    fun encryptedSamplesNeverQualifyForClearNativeMerger() {
        val report = TmeCmafPreflight.assess(
            session(),
            listOf(evidence("world", encrypted = true), evidence("onboard"))
        )

        assertEquals(TmeCmafPreflightStatus.DRM_PROTECTED_REQUIRES_SECURE_PIPELINE, report.status)
        assertFalse(report.nativeMergeEligible)
    }

    @Test
    fun playlistEncryptionIsRespectedEvenIfTrackFlagsAreNotAvailable() {
        val report = TmeCmafPreflight.assess(
            session(),
            listOf(evidence("world", encryptionMethod = "SAMPLE-AES-CTR"), evidence("onboard"))
        )

        assertEquals(TmeCmafPreflightStatus.DRM_PROTECTED_REQUIRES_SECURE_PIPELINE, report.status)
        assertFalse(report.nativeMergeEligible)
    }

    @Test
    fun differingCodecConfigurationsFailBeforeMergerConfiguration() {
        val report = TmeCmafPreflight.assess(
            session(),
            listOf(evidence("world"), evidence("onboard", fingerprint = "different-hvcc"))
        )

        assertEquals(TmeCmafPreflightStatus.CODEC_CONFIG_MISMATCH, report.status)
        assertFalse(report.nativeMergeEligible)
    }

    @Test
    fun misalignedTimestampsFailBeforeMergerConfiguration() {
        val report = TmeCmafPreflight.assess(
            session(),
            listOf(evidence("world", timeUs = 1_000_000L), evidence("onboard", timeUs = 1_100_001L))
        )

        assertEquals(TmeCmafPreflightStatus.TIMELINE_NOT_ALIGNED, report.status)
        assertFalse(report.nativeMergeEligible)
    }

    @Test
    fun dimensionMismatchFailsBeforeMergerConfiguration() {
        val report = TmeCmafPreflight.assess(
            session(),
            listOf(evidence("world"), evidence("onboard", width = 1920, height = 1080))
        )

        assertEquals(TmeCmafPreflightStatus.TILE_GEOMETRY_MISMATCH, report.status)
        assertFalse(report.nativeMergeEligible)
    }
}
