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

    @Test
    fun matchesGeoJsonCircuitByExactVenueAlias() {
        val client = TrackMapClient()
        assertEquals(90, client.scoreGeoJsonCircuit("albertparkcircuit", "melbourne", "melbourne"))
        assertEquals(90, client.scoreGeoJsonCircuit("bahraininternationalcircuit", "sakhir", "sakhir"))
    }

    @Test
    fun exactCircuitNameOutranksLocationAndAmbiguousAliasesStayRejectable() {
        val client = TrackMapClient()
        assertEquals(100, client.scoreGeoJsonCircuit("melbourne", "melbourne", "melbourne"))
        assertEquals(80, client.scoreGeoJsonCircuit("albertparkcircuit", "melbourne", "albertpark"))
        assertEquals(0, client.scoreGeoJsonCircuit("unknowncircuit", "unknown", ""))
    }

    @Test
    fun acceptsAnAccuratelyAlignedCentrelineFit() {
        val client = TrackMapClient()
        val matches = listOf(
            Triple(0.0, 0.0, 0.0 to 0.0),
            Triple(1.0, 0.0, 10.0 to 0.0),
            Triple(0.0, 1.0, 0.0 to 10.0),
            Triple(1.0, 1.0, 10.0 to 10.0)
        )
        val residual = client.normalizedFitResidual(
            matches,
            doubleArrayOf(10.0, 0.0, 0.0),
            doubleArrayOf(0.0, 10.0, 0.0)
        )
        assertEquals(0.0, residual!!, 1e-9)
    }

    @Test
    fun reportsLargeResidualForMismatchedCircuitAlignment() {
        val client = TrackMapClient()
        val matches = listOf(
            Triple(0.0, 0.0, 0.0 to 0.0),
            Triple(1.0, 0.0, 10.0 to 0.0),
            Triple(0.0, 1.0, 0.0 to 10.0),
            Triple(1.0, 1.0, 0.0 to 10.0)
        )
        val residual = client.normalizedFitResidual(
            matches,
            doubleArrayOf(10.0, 0.0, 0.0),
            doubleArrayOf(0.0, 10.0, 0.0)
        )
        assert(residual != null && residual > 0.12)
    }

    @Test
    fun rejectsDegenerateCentrelineFitValidation() {
        val client = TrackMapClient()
        val matches = listOf(
            Triple(0.0, 0.0, 2.0 to 2.0),
            Triple(1.0, 0.0, 2.0 to 2.0),
            Triple(0.0, 1.0, 2.0 to 2.0)
        )
        assertEquals(null, client.normalizedFitResidual(
            matches,
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0)
        ))
    }
}
