package app.f1multiview.data.f1tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class F1TvApiClientTest {

    @Test
    fun prioritizesServerConfiguredPlaybackApiVersion() {
        assertEquals(
            listOf("3.0", "2.0"),
            F1TvApiClient.apiVersionCandidates("3.0", listOf("2.0", "3.0"))
        )
    }

    @Test
    fun rejectsInvalidConfiguredApiVersionAndUsesLegacyFallbacks() {
        assertEquals(
            listOf("2.0", "3.0"),
            F1TvApiClient.apiVersionCandidates("../3.0", listOf("2.0", "3.0"))
        )
    }

    @Test
    fun preservesConfiguredVideoApiVersionAndLegacyFallback() {
        assertEquals(
            listOf("4.0", "3.0"),
            F1TvApiClient.apiVersionCandidates("4.0", listOf("4.0", "3.0"))
        )
    }


    @Test
    fun prefersTmeResponseOverEarlierNormalPlaybackResponse() {
        val normal = PlaybackResponse(
            manifestUrl = "https://example.com/normal.mpd",
            licenseUrl = null,
            drmToken = null,
            entitlementToken = null,
            playToken = null,
            streamType = "DASH",
            requestedApiVersion = "3.0"
        )
        val tme = normal.copy(
            tmeJson = """{"feeds":[{"url":"https://example/a"},{"url":"https://example/b"}]}""",
            requestedApiVersion = "2.0"
        )

        assertEquals(tme, F1TvApiClient.preferTmeResponse(normal, tme))
    }

    @Test
    fun preservesFirstNormalPlaybackResponseWhenNoCandidateHasTme() {
        val first = PlaybackResponse(
            manifestUrl = "https://example.com/first.mpd",
            licenseUrl = null,
            drmToken = null,
            entitlementToken = null,
            playToken = null,
            streamType = "DASH",
            requestedApiVersion = "3.0"
        )
        val second = first.copy(manifestUrl = "https://example.com/second.mpd", requestedApiVersion = "2.0")

        assertEquals(first, F1TvApiClient.preferTmeResponse(null, first))
        assertEquals(first, F1TvApiClient.preferTmeResponse(first, second))
    }

    @Test
    fun parsesProductionStyleTopLevelTmeJson() {
        val response = HttpResponse(
            200,
            true,
            """
            {
              "url": "https://example.com/live.mpd",
              "tmeJson": {
                "version": 1,
                "channel": "TME",
                "metadata": {"content_id": 1000009157},
                "advanced": {
                  "tile_size": {"width": 960, "height": 544},
                  "tile_count_horizontal": 2,
                  "tile_count_vertical": 2
                },
                "feeds": [
                  {"uuid":"a","url":"https://example/a","tile_index":0},
                  {"uuid":"b","url":"https://example/b","tile_index":1}
                ]
              },
              "isTmeAvailable": true,
              "channelViewMode": "MULTIVIEW"
            }
            """.trimIndent()
        )

        val parsed = F1TvApiClient().parsePlaybackResponse(response, "1000009157", "1033", "WEB_DASH")

        assertNotNull(parsed.tmeJson)
        assertEquals("MULTIVIEW", parsed.channelViewMode)
        assertEquals("https://example.com/live.mpd", parsed.manifestUrl)
        assertEquals(true, TmePlaybackParser.parse(parsed.tmeJson!!)?.feeds?.size == 2)
    }


    @Test
    fun ignoresNullTopLevelTmeAndReadsResultObjTme() {
        val response = HttpResponse(
            200,
            true,
            """
            {
              "url": "https://example.com/live.mpd",
              "tmeJson": null,
              "isTmeAvailable": true,
              "resultObj": {
                "tmeJson": {
                  "version": 1,
                  "channel": "TME",
                  "advanced": {"tile_size": {"width": 960, "height": 544}},
                  "feeds": [
                    {"uuid":"a","url":"https://example/a","tile_index":0},
                    {"uuid":"b","url":"https://example/b","tile_index":1}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val parsed = F1TvApiClient().parsePlaybackResponse(response, "42", null, "WEB_DASH")

        assertNotNull(parsed.tmeJson)
        assertEquals(true, TmePlaybackParser.parse(parsed.tmeJson!!)?.feeds?.size == 2)
    }

    @Test
    fun discoversTmeNestedInsideResultObjSettings() {
        val response = HttpResponse(
            200,
            true,
            """
            {
              "resultObj": {
                "url": "https://example.com/live.mpd",
                "settings": {
                  "isTmeAvailable": true,
                  "tmeJson": {
                    "version": 1,
                    "channel": "TME",
                    "advanced": {
                      "tile_size": {"width": 960, "height": 544},
                      "tile_count_horizontal": 2,
                      "tile_count_vertical": 1
                    },
                    "feeds": [
                      {"uuid":"a","url":"https://example/shared.mpd","tile_index":0},
                      {"uuid":"b","url":"https://example/shared.mpd","tile_index":1}
                    ]
                  }
                }
              }
            }
            """.trimIndent()
        )

        val parsed = F1TvApiClient().parsePlaybackResponse(response, "42", null, "WEB_DASH")

        assertNotNull(parsed.tmeJson)
        assertEquals(true, TmePlaybackParser.parse(parsed.tmeJson!!)?.feeds?.size == 2)
        assertEquals(TmeTopology.SINGLE_MOSAIC_SOURCE, TmePlaybackParser.parse(parsed.tmeJson!!)?.topology)
    }

    @Test
    fun retainsResultObjTmeFallbackForOlderResponses() {
        val response = HttpResponse(
            200,
            true,
            """
            {
              "resultObj": {
                "url": "https://example.com/live.mpd",
                "tmeJson": {
                  "version": 1,
                  "channel": "TME",
                  "advanced": {"tile_size": {"width": 960, "height": 544}},
                  "feeds": [
                    {"uuid":"a","url":"https://example/a","tile_index":0},
                    {"uuid":"b","url":"https://example/b","tile_index":1}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val parsed = F1TvApiClient().parsePlaybackResponse(response, "42", null, "WEB_DASH")

        assertNotNull(parsed.tmeJson)
        assertEquals("https://example.com/live.mpd", parsed.manifestUrl)
    }
}
