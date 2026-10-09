package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnMultiviewEngineContractTest {
    @Test
    fun acceptsThreeIndependentSyntheticFeedsOnOneDeclaredTimeline() {
        val feeds = listOf("LEFT", "CENTER", "RIGHT").map { id ->
            FeedDescriptor(
                id = id,
                mediaUri = "/synthetic/$id.mp4",
                mimeType = "video/avc",
                width = 320,
                height = 180,
                isLive = false,
                timelineGroup = "synthetic-shared-clock"
            )
        }

        assertEquals(listOf("LEFT", "CENTER", "RIGHT"), feeds.map { it.id })
        assertEquals(setOf("synthetic-shared-clock"), feeds.mapNotNull { it.timelineGroup }.toSet())
        assertTrue(feeds.all { it.width == 320 && it.height == 180 })
    }

    @Test
    fun threeNormalizedViewportsCoverTheOutputWithoutOverlap() {
        val layout = ViewportLayout(
            id = "synthetic-three-up",
            viewports = listOf(
                FeedViewport("LEFT", 0f, 0f, 1f / 3f, 1f, zIndex = 0),
                FeedViewport("CENTER", 1f / 3f, 0f, 1f / 3f, 1f, zIndex = 1),
                FeedViewport("RIGHT", 2f / 3f, 0f, 1f / 3f, 1f, zIndex = 2)
            )
        )

        assertEquals(3, layout.viewports.size)
        assertEquals(listOf("LEFT", "CENTER", "RIGHT"), layout.viewports.map { it.feedId })
        assertEquals(1f, layout.viewports.last().x + layout.viewports.last().width, 0.0001f)
        assertTrue(layout.viewports.all { it.y == 0f && it.height == 1f })
    }

    @Test
    fun rejectsViewportThatExtendsPastOutputBounds() {
        val invalid = runCatching {
            FeedViewport("RIGHT", 0.8f, 0f, 0.3f, 1f)
        }
        assertTrue(invalid.isFailure)
    }

    @Test
    fun rejectsDuplicateFeedViewport() {
        val invalid = runCatching {
            ViewportLayout(
                id = "duplicate",
                viewports = listOf(
                    FeedViewport("LEFT", 0f, 0f, 0.5f, 1f),
                    FeedViewport("LEFT", 0.5f, 0f, 0.5f, 1f)
                )
            )
        }
        assertFalse(invalid.isSuccess)
    }
}
