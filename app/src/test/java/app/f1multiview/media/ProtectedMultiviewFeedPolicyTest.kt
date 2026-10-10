package app.f1multiview.media

import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedMultiviewFeedPolicyTest {
    @Test
    fun trackAndTimingKindsCountAsVideoBecauseTheirTilesUsePlayerTile() {
        assertTrue(ProtectedMultiviewFeedPolicy.isVideo(StreamKind.WORLD))
        assertTrue(ProtectedMultiviewFeedPolicy.isVideo(StreamKind.ONBOARD))
        assertTrue(ProtectedMultiviewFeedPolicy.isVideo(StreamKind.TIMING))
        assertTrue(ProtectedMultiviewFeedPolicy.isVideo(StreamKind.TRACK))
        assertTrue(ProtectedMultiviewFeedPolicy.isVideo(StreamKind.HELICAM))
        assertTrue(ProtectedMultiviewFeedPolicy.isVideo(StreamKind.DATA))
        assertTrue(ProtectedMultiviewFeedPolicy.isVideo(StreamKind.F1_DASH))
    }

    @Test
    fun onlyUiDataPanelsAreExcludedFromProtectedVideoCount() {
        assertFalse(ProtectedMultiviewFeedPolicy.isVideo(StreamKind.TRACK_MAP))
        assertFalse(ProtectedMultiviewFeedPolicy.isVideo(StreamKind.F1_DASH_DATA))
    }

    @Test
    fun twoVideoFeedsTriggerProtectedPathEvenWhenOneIsTrackOrTiming() {
        val streams = listOf(
            StreamSource(id = "world", title = "World", kind = StreamKind.WORLD),
            StreamSource(id = "track", title = "Track", kind = StreamKind.TRACK),
            StreamSource(id = "map", title = "Map", kind = StreamKind.TRACK_MAP)
        )

        assertEquals(2, ProtectedMultiviewFeedPolicy.visibleVideoCount(streams, setOf("world", "track", "map")))
        assertEquals(listOf("world", "track"),
            ProtectedMultiviewFeedPolicy.selectedVideoStreams(streams, setOf("world", "track", "map")).map { it.id })
    }

    @Test
    fun unknownVisibleIdsDoNotCountAsVideoFeeds() {
        val streams = listOf(StreamSource(id = "world", title = "World", kind = StreamKind.WORLD))
        assertEquals(1, ProtectedMultiviewFeedPolicy.visibleVideoCount(streams, setOf("world", "unknown")))
    }
}
