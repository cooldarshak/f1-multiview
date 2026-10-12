package app.f1multiview.data.f1tv

import app.f1multiview.model.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedF1TvGatewayTest {
    @Test
    fun appOwnedDashboardSourcesExistWithoutAdditionalF1MediaStreams() {
        val sessionId = "race-session-17"
        val sources = appOwnedDashboardSources(sessionId)

        assertEquals(
            listOf(StreamKind.F1_DASH_DATA, StreamKind.TRACK_MAP),
            sources.map { it.kind }
        )
        assertEquals(
            listOf("f1-dash-data-race-session-17", "track-map-race-session-17"),
            sources.map { it.id }
        )
        assertTrue(sources.all { it.contentId == null && it.channelId == null && it.url == null })
        assertNull(sources.first().drmLicenseUrl)
        assertNull(sources.last().drmLicenseUrl)
    }
}
