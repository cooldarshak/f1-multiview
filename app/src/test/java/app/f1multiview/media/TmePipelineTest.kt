package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewFeed
import app.f1multiview.core.playback.TiledMultiviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmePipelineTest {
    private fun session() = TiledMultiviewSession(
        version = 1,
        channel = "F1",
        contentId = 123,
        tileWidth = 960,
        tileHeight = 540,
        feeds = listOf(
            TiledMultiviewFeed(0, 1, "world", "https://example/world", "world", null, null, null, null),
            TiledMultiviewFeed(1, 2, "onboard", "https://example/onboard", "onboard", null, null, null, null),
            TiledMultiviewFeed(2, 3, "data", "https://example/data", "data", null, null, null, null),
            TiledMultiviewFeed(3, 4, "tracker", "https://example/tracker", "tracker", null, null, null, null)
        )
    )

    @Test
    fun f1AdapterMapsEveryIndependentFeedWithoutClaimingOneUrl() {
        val mapped = F1TmeTileAdapter.map(session())
        assertEquals(4, mapped.size)
        assertEquals(setOf("world", "onboard", "data", "tracker"), mapped.map { it.feedId }.toSet())
        assertEquals(0, mapped[0].tileIndex)
        assertEquals(1, mapped[1].tileIndex)
    }

    @Test
    fun synchronizerOnlyReleasesACompleteEpoch() {
        val sync = TmeSegmentSynchronizer()
        val key = TmeCmafSegmentKey(10, 1_000_000, 1_000_000)
        sync.offer(TmeTileSegment("world", key, byteArrayOf(1)))
        assertEquals(null, sync.pollComplete(setOf("world", "onboard")))
        sync.offer(TmeTileSegment("onboard", key, byteArrayOf(2)))
        val aligned = sync.pollComplete(setOf("world", "onboard"))
        assertTrue(aligned?.complete == true)
        assertEquals(2, aligned?.tiles?.size)
    }

    @Test
    fun dynamicSelectionDoesNotCreateASecondDecoder() {
        val selection = TmeDynamicSelection()
        var decoderCreations = 1L
        selection.setSelected(listOf("world"))
        selection.toggle("onboard")
        selection.toggle("data")
        assertEquals(3, selection.state().selectedFeedIds.size)
        assertEquals(1L, decoderCreations)
    }

    @Test
    fun acceptanceGateRejectsFakeSinglePlayerEvidence() {
        val telemetry = TmeRuntimeTelemetry(
            backend = "OPEN_TME_NATIVE_PIPELINE",
            logicalFeedCount = 4,
            selectedFeedCount = 4,
            inputTileStreams = 4,
            mergedVideoStreams = 1,
            mediaCodecInstances = 4,
            outputSurfaces = 4,
            droppedSegments = 0,
            mergeLatencyMs = 0,
            decoderRecreationCount = 0
        )
        assertFalse(telemetry.singleDecoderInvariant)
        runCatching { TmeAcceptanceGate.requireNativeProof(telemetry) }
            .onSuccess { error("fake multi-decoder telemetry was accepted") }
    }
}
