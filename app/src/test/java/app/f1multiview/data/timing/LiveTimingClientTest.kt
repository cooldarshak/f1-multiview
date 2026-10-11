package app.f1multiview.data.timing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

class LiveTimingClientTest {
    @Test
    fun decodesRawDeflateBase64SignalRFeedPayload() {
        val json = """{"Lines":{"44":{"Position":1,"Tla":"HAM","GapToLeader":"LEADER"}}}"""
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val compressed = ByteArrayOutputStream()
        try {
            deflater.setInput(json.toByteArray(Charsets.UTF_8))
            deflater.finish()
            val buffer = ByteArray(256)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                compressed.write(buffer, 0, count)
            }
        } finally {
            deflater.end()
        }

        val encoded = Base64.getEncoder().encodeToString(compressed.toByteArray())
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        val decoded = client.decodeFeedObject(encoded)

        assertEquals("1", decoded?.getJSONObject("Lines")?.getJSONObject("44")?.getString("Position"))
        assertEquals("HAM", decoded?.getJSONObject("Lines")?.getJSONObject("44")?.getString("Tla"))
    }

    @Test
    fun acceptsJsonFeedObjectsAndWrapsPositionArrays() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        val direct = client.decodeFeedObject(JSONObject("""{"Lines":{}}"""))
        val array = client.decodeFeedObject(org.json.JSONArray("""[{"Entries":{}}]"""))

        assertTrue(direct?.has("Lines") == true)
        assertEquals(1, array?.getJSONArray("Position")?.length())
    }

    @Test
    fun malformedCompressedFeedPayloadReturnsUnavailableInsteadOfFakeData() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        assertNull(client.decodeFeedObject("definitely-not-json-or-compressed-data"))
    }

    @Test
    fun parsesRealPositionPayloadShapesAndKeepsPreviousSample() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parsePosition(
            JSONObject(
                """{"Position":[{"Timestamp":"2026-10-11T18:00:00Z","Entries":{"1":{"X":12.5,"Y":-7.25,"Z":1.0},"44":{"X":3.0,"Y":9.0}}}]}"""
            )
        )
        val first = client.trackPositions.value.first { it.number == "1" }
        assertEquals(12.5, first.x, 0.0001)
        assertEquals(-7.25, first.y, 0.0001)
        assertEquals(1, first.trail.size)

        client.parsePosition(
            JSONObject("""{"Position":[{"Entries":{"1":{"X":15.5,"Y":-2.25,"Z":1.0}}}]}""")
        )
        val second = client.trackPositions.value.first { it.number == "1" }
        assertEquals(15.5, second.x, 0.0001)
        assertEquals(-2.25, second.y, 0.0001)
        assertEquals(12.5, second.previousX!!, 0.0001)
        assertEquals(-7.25, second.previousY!!, 0.0001)
        assertTrue(second.previousUpdatedAtMs > 0L)
        assertTrue(second.updatedAtMs >= second.previousUpdatedAtMs)
        assertEquals(2, second.trail.size)
    }

    @Test
    fun malformedPositionEntriesNeverCreateCoordinates() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parsePosition(JSONObject("""{"Position":[{"Entries":{"1":{"X":"not-x","Y":2.0},"2":{"X":1.0}}}]}"""))
        assertTrue(client.trackPositions.value.isEmpty())
    }

    @Test
    fun timingTowerRowsRetainSparseFieldsAndFastestLapFlags() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseTiming(
            JSONObject(
                """{"Lines":{"1":{"Position":1,"Tla":"VER","GapToLeader":"LEADER","LastLapTime":{"Value":"1:32.100","Sector1":{"Value":"25.100","OverallFastest":true},"Sector2":{"Value":"33.200","PersonalFastest":true},"Sector3":{"Value":"33.800"}},"BestLapTime":{"Value":"1:31.000","OverallFastest":true}},"44":{"Position":2,"Tla":"HAM","GapToLeader":"+1.2","IntervalToPositionAhead":{"Value":"+1.2"}}}}"""
            )
        )
        client.parseTiming(JSONObject("""{"Lines":{"1":{"LastLapTime":{"Value":"1:31.900"}},"44":{"GapToLeader":"+0.8"}}}"""))

        val leader = client.rows.value.first { it.driverNumber == "1" }
        val second = client.rows.value.first { it.driverNumber == "44" }
        assertEquals("VER", leader.driver)
        assertEquals("1:31.900", leader.lastLap)
        assertEquals("25.100", leader.sector1)
        assertEquals("PURPLE", leader.sector1Status)
        assertEquals("GREEN", leader.sector2Status)
        assertEquals("1:31.000", leader.bestLap)
        assertEquals("PURPLE", leader.bestLapStatus)
        assertEquals("+0.8", second.leaderGap)
        assertEquals("+1.2", second.interval)
    }

    @Test
    fun sparseArrayDeltasPreserveUntouchedSectorSegments() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        val target = JSONObject(
            """{"1":{"LastLapTime":{"Sector1":{"Segments":[{"Status":2049},{"Status":2051},{"Status":2048}]}}}}"""
        )
        val patch = JSONObject(
            """{"1":{"LastLapTime":{"Sector1":{"Segments":[{"Status":2048}]}}}}"""
        )

        client.mergeJsonObject(target, patch)

        val segments = target.getJSONObject("1").getJSONObject("LastLapTime")
            .getJSONObject("Sector1").getJSONArray("Segments")
        assertEquals(3, segments.length())
        assertEquals(2048, segments.getJSONObject(0).getInt("Status"))
        assertEquals(2051, segments.getJSONObject(1).getInt("Status"))
        assertEquals(2048, segments.getJSONObject(2).getInt("Status"))
    }

    @Test
    fun sparseTimingUpdatesPreserveUntouchedNestedFields() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        val current = JSONObject(
            """{"1":{"Position":1,"GapToLeader":"LEADER","LastLapTime":{"Value":"1:32.100","OverallFastest":true}},"44":{"Position":2,"GapToLeader":"+1.0"}}"""
        )
        val delta = JSONObject("""{"1":{"LastLapTime":{"Value":"1:31.900"}}}""")

        client.mergeJsonObject(current, delta)

        val leader = current.getJSONObject("1")
        assertEquals(1, leader.getInt("Position"))
        assertEquals("LEADER", leader.getString("GapToLeader"))
        assertEquals("1:31.900", leader.getJSONObject("LastLapTime").getString("Value"))
        assertTrue(leader.getJSONObject("LastLapTime").getBoolean("OverallFastest"))
        assertEquals("+1.0", current.getJSONObject("44").getString("GapToLeader"))
    }
}
