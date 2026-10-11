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

    @Test
    fun rejectsAmbiguousHighestScoringCircuitCandidates() {
        val client = TrackMapClient()
        assertEquals(1, client.uniqueBestIndex(listOf(10, 100, 20), minimumScore = 80))
        assertEquals(null, client.uniqueBestIndex(listOf(100, 100, 20), minimumScore = 80))
        assertEquals(null, client.uniqueBestIndex(listOf(60, 40), minimumScore = 70))
        assertEquals(null, client.uniqueBestIndex(emptyList(), minimumScore = 70))
    }

    @Test
    fun suffixOnlyCircuitLabelDoesNotProduceAMatchableName() {
        assertEquals("", TrackMapClient().normalize("GP"))
        assertEquals("", TrackMapClient().normalize("Circuit"))
    }
}
