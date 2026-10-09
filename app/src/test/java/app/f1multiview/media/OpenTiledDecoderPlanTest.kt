package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewFeed
import app.f1multiview.core.playback.TiledMultiviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenTiledDecoderPlanTest {
    private fun session(
        urls: List<String>,
        columns: Int? = null,
        rows: Int? = null
    ) = TiledMultiviewSession(
        version = 1,
        channel = "multiview",
        contentId = 42,
        tileWidth = 960,
        tileHeight = 540,
        tileCountHorizontal = columns,
        tileCountVertical = rows,
        feeds = urls.mapIndexed { index, url ->
            TiledMultiviewFeed(
                index = index,
                channelId = index + 1,
                encoderId = "encoder-$index",
                url = url,
                uuid = "feed-$index",
                audioEnglish = null,
                audioSpanish = null,
                subtitleEnglish = null,
                subtitleSpanish = null,
                tileIndex = columns?.let { index },
                tileRow = columns?.let { index / it },
                tileColumn = columns?.let { index % it }
            )
        }
    )


    private fun explicitGridSession() = TiledMultiviewSession(
        version = 1,
        channel = "multiview",
        contentId = 43,
        tileWidth = 960,
        tileHeight = 540,
        tileCountHorizontal = 2,
        tileCountVertical = 1,
        feeds = listOf(
            TiledMultiviewFeed(0, 1, "world", "https://cdn/mosaic.m3u8", "world", null, null, null, null, tileIndex = 0, tileRow = 0, tileColumn = 0),
            TiledMultiviewFeed(1, 2, "onboard", "https://cdn/mosaic.m3u8", "onboard", null, null, null, null, tileIndex = 1, tileRow = 0, tileColumn = 1)
        )
    )

    @Test
    fun explicitGridRequiresActualDecodedDimensionsToMatchMosaicCanvas() {
        assertNotNull(OpenTiledDecoderPlan.from(explicitGridSession(), 1920, 540))
        assertNull(OpenTiledDecoderPlan.from(explicitGridSession(), 960, 540))
    }

    @Test
    fun singleMosaicProducesOnePhysicalDecoderAndManyLogicalMappings() {
        val plan = OpenTiledDecoderPlan.from(
            session(listOf("https://cdn/mosaic.m3u8", "https://cdn/mosaic.m3u8", "https://cdn/mosaic.m3u8", "https://cdn/mosaic.m3u8"), columns = 2, rows = 2),
            sourceVideoWidth = 1920,
            sourceVideoHeight = 1080
        )
        assertNotNull(plan)
        assertEquals(1, plan!!.physicalDecoderCount)
        assertEquals(4, plan.logicalFeedCount)
        assertEquals(4, plan.bindings.size)
        assertEquals(2, plan.tileColumns)
        assertEquals(2, plan.tileRows)
    }

    @Test
    fun independentUrlsAreRejected() {
        val plan = OpenTiledDecoderPlan.from(
            session(listOf("https://cdn/1.m3u8", "https://cdn/2.m3u8"))
        )
        assertNull(plan)
    }

    @Test
    fun twentyFourLogicalFeedsStillUseOnePhysicalDecoder() {
        val urls = List(24) { "https://cdn/mosaic.mpd" }
        val plan = OpenTiledDecoderPlan.from(
            session(urls, columns = 6, rows = 4),
            // 6 x 4 tiles of 960 x 540 exactly fill a 5760 x 2160 source.
            sourceVideoWidth = 5760,
            sourceVideoHeight = 2160
        )
        assertNotNull(plan)
        assertEquals(1, plan!!.physicalDecoderCount)
        assertEquals(24, plan.logicalFeedCount)
        assertEquals(6, plan.tileColumns)
        assertEquals(4, plan.tileRows)
        assertEquals(24, plan.selectedBindings(plan.bindings.map { it.feedId }).size)
    }

    @Test
    fun sourceGeometryProducesNormalizedTileRectangles() {
        val plan = OpenTiledDecoderPlan.from(
            session(listOf("https://cdn/mosaic.mpd", "https://cdn/mosaic.mpd", "https://cdn/mosaic.mpd", "https://cdn/mosaic.mpd"), columns = 2, rows = 2),
            sourceVideoWidth = 1920,
            sourceVideoHeight = 1080
        )
        assertNotNull(plan)
        assertEquals(0f, plan!!.bindings[0].sourceRect.left, 0.0001f)
        assertEquals(0.5f, plan.bindings[0].sourceRect.right, 0.0001f)
        assertEquals(0.5f, plan.bindings[1].sourceRect.left, 0.0001f)
        assertEquals(1f, plan.bindings[3].sourceRect.right, 0.0001f)
        assertEquals(1f, plan.bindings[3].sourceRect.bottom, 0.0001f)
    }
}
