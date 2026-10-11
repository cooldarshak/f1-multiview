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
