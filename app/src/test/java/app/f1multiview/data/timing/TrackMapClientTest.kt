package app.f1multiview.data.timing

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMapClientTest {
    @Test
    fun normalizesReplaySessionNameToMeetingName() {
        assertEquals("singapore", TrackMapClient().normalize("Singapore GP: Qualifying 1"))
        assertEquals("singapore", TrackMapClient().normalize("Singapore Grand Prix"))
    }

    @Test
    fun normalizesCircuitNameForGeoJsonMatch() {
        assertEquals("marinabay", TrackMapClient().normalize("Marina Bay Street Circuit"))
        assertEquals("albertpark", TrackMapClient().normalize("Albert Park Grand Prix Circuit"))
    }
}
