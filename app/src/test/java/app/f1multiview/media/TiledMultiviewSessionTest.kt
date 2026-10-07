package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewFeed
import app.f1multiview.core.playback.TiledMultiviewSession
import org.junit.Assert.assertEquals
import org.junit.Test

class TiledMultiviewSessionTest {
    @Test
    fun feedIdsPreferUuidAndRemainUnique() {
        val session = TiledMultiviewSession(
            version = 1,
            channel = "F1",
            contentId = 1,
            tileWidth = 1920,
            tileHeight = 1080,
            feeds = listOf(
                TiledMultiviewFeed(0, 11, "world", "u1", "same", null, null, null, null),
                TiledMultiviewFeed(1, 12, "onboard", "u2", "same", null, null, null, null),
                TiledMultiviewFeed(2, 13, "data", "u3", "", null, null, null, null)
            )
        )

        assertEquals(listOf("same", "same#1", "13"), session.feedIds)
    }
}
