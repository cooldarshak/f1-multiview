package app.f1multiview.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TiledMultiviewSessionTest {
    @Test
    fun duplicateFeedIdentifiersBecomeStableUniqueLogicalIds() {
        val session = TiledMultiviewSession(
            version = 1,
            channel = "multiview",
            contentId = 1,
            tileWidth = 960,
            tileHeight = 540,
            feeds = listOf(
                TiledMultiviewFeed(0, 1, "encoder-a", "https://cdn/mosaic.mpd", "same", null, null, null, null),
                TiledMultiviewFeed(1, 2, "encoder-b", "https://cdn/mosaic.mpd", "same", null, null, null, null),
                TiledMultiviewFeed(2, 3, "encoder-c", "https://cdn/mosaic.mpd", "third", null, null, null, null)
            )
        )

        assertEquals(listOf("same", "same#1", "third"), session.feedIds)
    }
}
