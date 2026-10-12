package app.f1multiview.data.f1tv

import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedF1TvGatewayTest {
    @Test
    fun singleAssetRaceKeepsWorldFeedAndAddsNativeTimingAndTrackerPanels() {
        val sessionId = "race-session-17"
        val world = StreamSource(
            id = "vod-race-session-17",
            title = "Race",
            kind = StreamKind.WORLD,
            contentId = sessionId,
            isLive = false
        )

        val sources = mergeAppOwnedDashboardSources(sessionId, listOf(world), includeNativeDashboard = true)

        assertEquals(
            listOf(StreamKind.WORLD, StreamKind.F1_DASH_DATA, StreamKind.TRACK_MAP),
            sources.map { it.kind }
        )
        assertSame(world, sources.first())
        assertEquals(
            listOf("vod-race-session-17", "f1-dash-data-race-session-17", "track-map-race-session-17"),
            sources.map { it.id }
        )
        assertTrue(sources.drop(1).all {
            it.contentId == null && it.channelId == null && it.url == null && it.drmLicenseUrl == null
        })
    }

    @Test
    fun editorialSessionDoesNotGetRaceSpecificNativePanels() {
        val editorial = listOf(
            StreamSource("vod-show-1", "Race Show", StreamKind.WORLD, contentId = "show-1", isLive = false)
        )

        val sources = mergeAppOwnedDashboardSources("show-1", editorial, includeNativeDashboard = false)

        assertSame(editorial, sources)
        assertEquals(1, sources.size)
    }

    @Test
    fun mergingDashboardSourcesDoesNotDuplicateExistingPanels() {
        val sessionId = "race-session-17"
        val existing = appOwnedDashboardSources(sessionId)
        val sources = mergeAppOwnedDashboardSources(
            sessionId,
            existing,
            includeNativeDashboard = true
        )

        assertEquals(2, sources.size)
        assertEquals(listOf(StreamKind.F1_DASH_DATA, StreamKind.TRACK_MAP), sources.map { it.kind })
    }
}
