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
    fun subscriptionAckDoesNotOverwriteLiveStateWhenRowsArriveFirst() {
        assertEquals("LIVE", liveTimingStatusAfterSubscriptionAck(10_000L))
        assertEquals("SUBSCRIBED", liveTimingStatusAfterSubscriptionAck(0L))
    }

    @Test
    fun noDataWatchdogOnlyExpiresAnAcknowledgedOrPendingSubscription() {
        assertEquals("NO_DATA", liveTimingNoDataStatus("SUBSCRIBED", 0L, 1_000L, 16_000L))
        assertEquals("NO_DATA", liveTimingNoDataStatus("SUBSCRIBE_SENT", 0L, 1_000L, 16_000L))
        assertEquals(null, liveTimingNoDataStatus("SUBSCRIBED", 0L, 1_000L, 15_999L))
        assertEquals(null, liveTimingNoDataStatus("LIVE", 0L, 1_000L, 30_000L))
        assertEquals(null, liveTimingNoDataStatus("SUBSCRIBED", 2_000L, 1_000L, 30_000L))
        assertEquals(null, liveTimingNoDataStatus("SUBSCRIBED", 0L, 0L, 30_000L))
    }

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

        assertEquals("1", decoded?.getJSONObject("Lines")?.getJSONObject("44")?.optString("Position"))
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
    fun joinsCarDataGearRpmAndDrsIntoExistingTimingRows() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseTiming(
            JSONObject("""{"Lines":{"1":{"Position":1,"Tla":"VER","LastLapTime":{"Value":"1:31.100"}}}}""")
        )
        client.parseCarData(
            JSONObject(
                """{"Entries":[{"Cars":{"1":{"Channels":{"0":12850,"2":318,"3":8,"4":100,"5":0,"45":8}}}}]}"""
            )
        )

        val row = client.rows.value.single()
        assertEquals("318", row.speed)
        assertEquals(12850, row.rpm)
        assertEquals(8, row.gear)
        assertEquals(true, row.drsActive)
        assertEquals(318, client.telemetry.value.single().speed)
        assertEquals(8, client.telemetry.value.single().drs)
    }

    @Test
    fun malformedPositionEntriesNeverCreateCoordinates() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parsePosition(JSONObject("""{"Position":[{"Entries":{"1":{"X":"not-x","Y":2.0},"2":{"X":1.0}}}]}"""))
        assertTrue(client.trackPositions.value.isEmpty())
    }

    @Test
    fun parsesObjectValuedLiveTimingPosition() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseTiming(JSONObject(
            """{"Lines":{"44":{"Position":{"Value":"2"},"Tla":"HAM","GapToLeader":"+1.2"}}}"""
        ))

        val row = client.rows.value.single()
        assertEquals(2, row.position)
        assertEquals("HAM", row.driver)
        assertEquals("+1.2", row.leaderGap)
    }

    @Test
    fun timingTowerRowsRetainSparseFieldsAndFastestLapFlags() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseTiming(
            JSONObject(
                """{"Lines":{"1":{"Position":1,"Tla":"VER","GapToLeader":"LEADER","InPit":true,"PitOut":false,"Stopped":true,"Retired":false,"LastLapTime":{"Value":"1:32.100","Sector1":{"Value":"25.100","OverallFastest":true},"Sector2":{"Value":"33.200","PersonalFastest":true},"Sector3":{"Value":"33.800"}},"BestLapTime":{"Value":"1:31.000","OverallFastest":true}},"44":{"Position":2,"Tla":"HAM","GapToLeader":"+1.2","IntervalToPositionAhead":{"Value":"+1.2"}}}}"""
            )
        )
        client.parseTiming(JSONObject("""{"Lines":{"1":{"LastLapTime":{"Value":"1:31.900"}},"44":{"GapToLeader":"+0.8"}}}"""))

        val leader = client.rows.value.first { it.driverNumber == "1" }
        val second = client.rows.value.first { it.driverNumber == "44" }
        assertEquals("VER", leader.driver)
        assertEquals("1:31.900", leader.lastLap)
        assertTrue(leader.inPit)
        assertTrue(leader.stopped)
        assertEquals("25.100", leader.sector1)
        assertEquals("PURPLE", leader.sector1Status)
        assertEquals("GREEN", leader.sector2Status)
        assertEquals("1:31.000", leader.bestLap)
        assertEquals("PURPLE", leader.bestLapStatus)
        assertEquals("+0.8", second.leaderGap)
        assertEquals("+1.2", second.interval)
    }

    @Test
    fun newSessionIdentityClearsRowsPositionsAndSessionScopedCaches() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseSessionInfo(JSONObject(
            """{"SessionInfo":{"Name":"Race","Type":"Race","StartDate":"2026-10-11T12:00:00Z","MeetingName":"Singapore Grand Prix","Meeting":{"Name":"Singapore Grand Prix","Circuit":{"Key":61,"Name":"Marina Bay Street Circuit"}},"Country":{"Name":"Singapore"}}}"""
        ))
        client.parseTiming(JSONObject("""{"Lines":{"1":{"Position":1,"Tla":"VER","GapToLeader":"LEADER","LastLapTime":{"Value":"1:32.100"}}}}"""))
        client.parsePosition(JSONObject("""{"Position":[{"Entries":{"1":{"X":12.5,"Y":-7.25,"Z":0.0}}}]}"""))
        assertEquals(1, client.rows.value.size)
        assertEquals(1, client.trackPositions.value.size)

        client.parseSessionInfo(JSONObject(
            """{"SessionInfo":{"Name":"Qualifying","Type":"Qualifying","StartDate":"2026-10-10T12:00:00Z","MeetingName":"Singapore Grand Prix","Meeting":{"Name":"Singapore Grand Prix","Circuit":{"Key":61,"Name":"Marina Bay Street Circuit"}},"Country":{"Name":"Singapore"}}}"""
        ))

        assertTrue(client.rows.value.isEmpty())
        assertTrue(client.trackPositions.value.isEmpty())
        assertTrue(client.telemetry.value.isEmpty())
        assertEquals("Qualifying", client.sessionInfo.value.name)
    }

    @Test
    fun partialSessionMetadataDoesNotEraseRowsOrCurrentSessionIdentity() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseSessionInfo(JSONObject(
            """{"SessionInfo":{"Name":"Race","Type":"Race","StartDate":"2026-10-11T12:00:00Z","MeetingName":"Singapore Grand Prix","Meeting":{"Name":"Singapore Grand Prix","Circuit":{"Key":61,"Name":"Marina Bay Street Circuit"}}}}"""
        ))
        client.parseTiming(JSONObject("""{"Lines":{"1":{"Position":1,"Tla":"VER","GapToLeader":"LEADER"}}}"""))

        client.parseSessionInfo(JSONObject("""{"SessionInfo":{"Status":"Started"}}"""))

        assertEquals(1, client.rows.value.size)
        assertEquals("Race", client.sessionInfo.value.name)
        assertEquals("Singapore Grand Prix", client.sessionInfo.value.meeting)
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
    fun raceControlUiRetainsEarlierMessagesAcrossSparseUpdates() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseRaceControl(JSONObject(
            """{"Messages":[{"Utc":"2026-10-11T12:00:00Z","Message":"First message","Flag":"GREEN"},{"Utc":"2026-10-11T12:01:00Z","Message":"Second message","Flag":"INFO"}]}"""
        ))
        client.parseRaceControl(JSONObject(
            """{"Messages":{"1":{"Flag":"YELLOW"}}}"""
        ))

        val rows = client.raceControl.value
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.message == "First message" })
        val updated = rows.first { it.message == "Second message" }
        assertEquals("YELLOW", updated.severity)
    }
    @Test
    fun numericKeyDeltasMergeIntoExistingRaceControlArraySnapshots() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        val target = JSONObject(
            """{"RaceControlMessages":{"Messages":[{"Utc":"12:00:00","Message":"First"},{"Utc":"12:01:00","Message":"Second"}]}}"""
        )
        val patch = JSONObject(
            """{"RaceControlMessages":{"Messages":{"1":{"Flag":"YELLOW"}}}}"""
        )

        client.mergeJsonObject(target, patch)

        val messages = target.getJSONObject("RaceControlMessages").getJSONArray("Messages")
        assertEquals(2, messages.length())
        assertEquals("First", messages.getJSONObject(0).getString("Message"))
        assertEquals("Second", messages.getJSONObject(1).getString("Message"))
        assertEquals("YELLOW", messages.getJSONObject(1).getString("Flag"))
    }
    @Test
    fun parsesZeroBasedSectorObjectWithSegmentsAndFastestFlags() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseTiming(JSONObject(
            """{"Lines":{"1":{"Position":1,"Tla":"VER","Sectors":{"0":{"Value":"25.100","OverallFastest":true,"Segments":[{"Status":2049},{"Status":2051}]},"1":{"Value":"33.200","PersonalFastest":true,"Segments":[{"Status":2049}]},"2":{"Value":"34.000","Segments":[{"Status":2048}]}},"LastLapTime":{"Value":"1:32.300"}}}}"""
        ))

        val row = client.rows.value.single()
        assertEquals("25.100", row.sector1)
        assertEquals("PURPLE", row.sector1Status)
        assertEquals(listOf("GREEN","PURPLE"), row.sector1Segments)
        assertEquals("33.200", row.sector2)
        assertEquals("GREEN", row.sector2Status)
        assertEquals("34.000", row.sector3)
        assertEquals("NORMAL", row.sector3Status)
    }

    @Test
    fun missingLiveDriverMetadataUsesSourceNumberInsteadOfSyntheticAcronym() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseTiming(JSONObject(
            """{"Lines":{"3":{"Position":"1","GapToLeader":""}}}"""
        ))

        val row = client.rows.value.single()
        assertEquals(1, row.position)
        assertEquals("3", row.driver)
        assertEquals("3", row.driverNumber)
    }

    @Test
    fun blankLeaderIntervalDoesNotLeakJsonAndShowsLeaderState() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseTiming(JSONObject(
            """{"Lines":{"3":{"Position":"1","RacingNumber":"3","Tla":"VER","GapToLeader":"","IntervalToPositionAhead":{"Value":"","Catching":false}}}}"""
        ))

        val row = client.rows.value.single()
        assertEquals("LEADER", row.leaderGap)
        assertEquals("-", row.interval)
    }

    @Test
    fun mapsOfficialSectorStatusCodesWithoutFastestFlags() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseTiming(JSONObject(
            """{"Lines":{"1":{"Position":1,"Tla":"VER","Sectors":[{"Value":"25.100","Status":2048},{"Value":"33.200","Status":2064},{"Value":"34.000","Status":2051}]}}}"""
        ))

        val row = client.rows.value.single()
        assertEquals("YELLOW", row.sector1Status)
        assertEquals("BLUE", row.sector2Status)
        assertEquals("PURPLE", row.sector3Status)
    }

    @Test
    fun parsesOneBasedSectorObjectKeysWithoutReassigningSectorNumbers() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseTiming(JSONObject(
            """{"Lines":{"44":{"Position":2,"Tla":"HAM","Sectors":{"1":{"Value":"26.100"},"2":{"Value":"32.200"},"3":{"Value":"35.000"}}}}}"""
        ))

        val row = client.rows.value.single()
        assertEquals("26.100", row.sector1)
        assertEquals("32.200", row.sector2)
        assertEquals("35.000", row.sector3)
    }
    @Test
    fun parsesLapCountAsScalarsAndValueWrappedFieldsWithoutErasingPartialUpdates() {
        val client = LiveTimingClient(CoroutineScope(SupervisorJob()))
        client.parseLapCount(JSONObject("""{"CurrentLap":7,"TotalLaps":57}"""))
        assertEquals(7 to 57, client.lapCount.value)

        client.parseLapCount(JSONObject("""{"CurrentLap":{"Value":8}}"""))
        assertEquals(8 to 57, client.lapCount.value)

        client.parseLapCount(JSONObject("""{"TotalLaps":{"Value":"60"}}"""))
        assertEquals(8 to 60, client.lapCount.value)

        client.parseLapCount(JSONObject("""{"CurrentLap":0,"TotalLaps":0}"""))
        assertEquals(8 to 60, client.lapCount.value)
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
