package app.f1multiview.data.f1tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TmePlaybackParserTest {
    @Test
    fun mapsOfficialTileIndexUsingAuthoritativeHorizontalCount() {
        val json = """
            {
              "version": 1,
              "channel": "TME",
              "metadata": {"contentId": 42},
              "advanced": {
                "tileSize": {
                  "width": 960,
                  "height": 540
                },
                "tileCountHorizontal": 2,
                "tileCountVertical": 2
              },
              "feeds": [
                {"uuid":"a","url":"https://example/a","tileIndex":0},
                {"uuid":"b","url":"https://example/b","tileIndex":1},
                {"uuid":"c","url":"https://example/c","tileIndex":2},
                {"uuid":"d","url":"https://example/d","tileIndex":3}
              ]
            }
        """.trimIndent()

        val parsed = TmePlaybackParser.parse(json)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.tileCountHorizontal)
        assertEquals(2, parsed.tileCountVertical)
        assertEquals(0, parsed.feeds[0].tileRow)
        assertEquals(0, parsed.feeds[0].tileColumn)
        assertEquals(0, parsed.feeds[1].tileRow)
        assertEquals(1, parsed.feeds[1].tileColumn)
        assertEquals(1, parsed.feeds[2].tileRow)
        assertEquals(0, parsed.feeds[2].tileColumn)
        assertEquals(1, parsed.feeds[3].tileRow)
        assertEquals(1, parsed.feeds[3].tileColumn)
    }

    @Test
    fun usesResolvedTileCountForColumnWhenCountLivesOnFeedMetadata() {
        val json = """
            {
              "advanced": {"tileSize":{"width":960,"height":540}},
              "feeds": [
                {"uuid":"a","url":"https://example/a","tileIndex":0,
                 "metadata":{"tileCountHorizontal":2,"tileCountVertical":2}},
                {"uuid":"b","url":"https://example/b","tileIndex":1,
                 "metadata":{"tileCountHorizontal":2,"tileCountVertical":2}},
                {"uuid":"c","url":"https://example/c","tileIndex":2,
                 "metadata":{"tileCountHorizontal":2,"tileCountVertical":2}}
              ]
            }
        """.trimIndent()

        val parsed = TmePlaybackParser.parse(json)!!
        assertEquals(2, parsed.tileCountHorizontal)
        assertEquals(1, parsed.feeds[2].tileRow)
        assertEquals(0, parsed.feeds[2].tileColumn)
    }

    @Test
    fun refusesNativePlacementWhenTileCountMetadataIsAbsent() {
        val json = """
            {
              "advanced": {"tileSize":{"width":960,"height":540}},
              "feeds": [
                {"uuid":"a","url":"https://example/a","tileIndex":0},
                {"uuid":"b","url":"https://example/b","tileIndex":1}
              ]
            }
        """.trimIndent()

        val parsed = TmePlaybackParser.parse(json)!!
        assertEquals(null, parsed.feeds[0].tileRow)
        assertEquals(null, parsed.feeds[0].tileColumn)
    }
}
