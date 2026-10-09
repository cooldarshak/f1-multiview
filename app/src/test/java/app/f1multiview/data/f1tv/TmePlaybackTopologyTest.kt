package app.f1multiview.data.f1tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TmePlaybackTopologyTest {
    @Test
    fun identicalFeedUrlsWithTileGeometryAreSingleMosaic() {
        val tme = TmePlayback(
            version = 1,
            channel = "F1",
            contentId = 1,
            tileWidth = 960,
            tileHeight = 540,
            feeds = listOf(
                TmeFeed("World", null, 1, "world", "World", null, "https://example/mosaic.mpd", "w"),
                TmeFeed("Onboard", null, 2, "onboard", "Onboard", null, "https://example/mosaic.mpd", "o")
            )
        )

        assertEquals(TmeTopology.SINGLE_MOSAIC_SOURCE, tme.topology)
    }

    @Test
    fun independentFeedUrlsNeverClaimSinglePlayerTopology() {
        val tme = TmePlayback(
            version = 1,
            channel = "F1",
            contentId = 1,
            tileWidth = 960,
            tileHeight = 540,
            feeds = listOf(
                TmeFeed("World", null, 1, "world", "World", null, "https://example/world.mpd", "w"),
                TmeFeed("Onboard", null, 2, "onboard", "Onboard", null, "https://example/onboard.mpd", "o")
            )
        )

        assertEquals(TmeTopology.INDEPENDENT_FEED_SOURCES, tme.topology)
    }
    @Test
    fun missingFeedUrlNeverClaimsSingleMosaicTopology() {
        val tme = TmePlayback(
            version = 1,
            channel = "F1",
            contentId = 1,
            tileWidth = 960,
            tileHeight = 540,
            feeds = listOf(
                TmeFeed("World", null, 1, "world", "World", null, "https://example/mosaic.mpd", "w"),
                TmeFeed("Onboard", null, 2, "onboard", "Onboard", null, null, "o")
            )
        )
        assertEquals(TmeTopology.UNKNOWN, tme.topology)
    }

}
