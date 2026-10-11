package app.f1multiview.data.timing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun retriesOlderPublicCircuitCataloguesWhenCurrentSeasonLookupIsRestricted() {
        assertEquals(listOf(2026, 2025, 2024, 2023), TrackMapClient.catalogLookupYears(2026))
        assertEquals(listOf(2025, 2024, 2023), TrackMapClient.catalogLookupYears(2025))
        // For archived races, recent public catalogue data is still useful for stable venue keys.
        assertEquals(listOf(2023), TrackMapClient.catalogLookupYears(2021))
    }

    @Test
    fun geometryLookupFallsBackOnlyToPublishedPublicSeasons() {
        assertEquals(listOf(2026, 2025, 2024, 2023), TrackMapClient.geometryLookupYears(2026))
        assertEquals(listOf(2024, 2023), TrackMapClient.geometryLookupYears(2024))
        // Old archives are not silently mapped to a newer season's telemetry coordinate frame.
        assertEquals(listOf(2021), TrackMapClient.geometryLookupYears(2021))
    }

    @Test
    fun parsesNativeCircuitTraceInTelemetryCoordinates() {
        val x = JSONArray()
        val y = JSONArray()
        repeat(TrackMapClient.MIN_CENTERLINE_POINTS) { i ->
            x.put(i.toDouble())
            y.put((i % 7).toDouble())
        }
        val points = TrackMapClient().parseTrackCoordinates(x, y)
        assertEquals(TrackMapClient.MIN_CENTERLINE_POINTS, points.size)
        assertEquals(0.0, points.first().x, 0.0)
        assertEquals(19.0, points.last().x, 0.0)
    }

    @Test
    fun rejectsMalformedOrIncompleteNativeCircuitTrace() {
        val client = TrackMapClient()
        val x = JSONArray()
        val y = JSONArray()
        repeat(TrackMapClient.MIN_CENTERLINE_POINTS) { i ->
            x.put(i.toDouble())
            y.put((i % 7).toDouble())
        }
        assertTrue(client.parseTrackCoordinates(x, JSONArray()).isEmpty())
        y.put(99.0)
        assertTrue(client.parseTrackCoordinates(x, y).isEmpty())

        val badX = JSONArray()
        val badY = JSONArray()
        repeat(TrackMapClient.MIN_CENTERLINE_POINTS) { i ->
            badX.put(if (i == 4) JSONObject.NULL else i.toDouble())
            badY.put((i % 7).toDouble())
        }
        assertTrue(client.parseTrackCoordinates(badX, badY).isEmpty())
    }

    @Test
    fun rejectsDegenerateNativeCircuitTrace() {
        val x = JSONArray()
        val y = JSONArray()
        repeat(TrackMapClient.MIN_CENTERLINE_POINTS) {
            x.put(1.0)
            y.put(2.0)
        }
        assertTrue(TrackMapClient().parseTrackCoordinates(x, y).isEmpty())
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
