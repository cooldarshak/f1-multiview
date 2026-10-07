package app.f1multiview.media

import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportSchedulerTest {
    private fun feed(id: String) = StreamSource(
        id = id,
        title = id,
        kind = StreamKind.WORLD,
        url = "https://example.test/$id.m3u8"
    )

    @Test
    fun referenceAndVisibleFeedsArePrioritizedWithinDecoderBudget() {
        val streams = (1..6).map { feed("f$it") }
        val selected = ViewportScheduler(4).schedule(
            streams = streams,
            visibleIds = setOf("f2", "f3", "f4"),
            referenceId = "f5",
            activeIds = setOf("f1", "f2", "f3", "f4")
        ).map { it.id }

        assertEquals(listOf("f5", "f2", "f3", "f4"), selected)
    }

    @Test
    fun visibleColdFeedCanReplaceInvisibleActiveFeed() {
        val streams = (1..5).map { feed("f$it") }
        val selected = ViewportScheduler(4).schedule(
            streams = streams,
            visibleIds = setOf("f2", "f3", "f4", "f5"),
            referenceId = "f2",
            activeIds = setOf("f1", "f2", "f3", "f4")
        ).map { it.id }

        assertTrue(selected.contains("f5"))
        assertFalse(selected.contains("f1"))
    }

    @Test
    fun nonVideoFeedsNeverConsumeDecoderSlots() {
        val streams = listOf(
            feed("video"),
            feed("timing").copy(kind = StreamKind.TIMING),
            feed("track").copy(kind = StreamKind.TRACK_MAP),
            feed("dash").copy(kind = StreamKind.F1_DASH_DATA)
        )
        val selected = ViewportScheduler(4).schedule(
            streams = streams,
            visibleIds = streams.map { it.id }.toSet(),
            referenceId = "video",
            activeIds = emptySet()
        ).map { it.id }

        assertEquals(listOf("video", "timing"), selected)
    }

    @Test
    fun duplicateLogicalFeedIdsAreReturnedOnce() {
        val streams = listOf(feed("a"), feed("a"), feed("b"))
        val selected = ViewportScheduler(4).schedule(
            streams = streams,
            visibleIds = setOf("a", "b"),
            referenceId = "a",
            activeIds = emptySet()
        )
        assertEquals(2, selected.size)
    }
}
