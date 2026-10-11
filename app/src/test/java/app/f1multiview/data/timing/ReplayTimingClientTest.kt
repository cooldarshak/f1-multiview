package app.f1multiview.data.timing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.assertTrue

class ReplayTimingClientTest {
    @Test
    fun archiveLookupUsesSessionRelativeMillisecondsNotAbsoluteUtcEpoch() {
        val client = ReplayTimingClient()
        assertEquals(0L, client.archiveOffsetForVideoPosition(0L))
        assertEquals(2_500L, client.archiveOffsetForVideoPosition(2_500L))
        client.setSyncOffset(750L)
        assertEquals(3_250L, client.archiveOffsetForVideoPosition(2_500L))
    }

    @Test
    fun curatedVideoSessionStartMapsToLightsOutArchiveOffset() {
        val client = ReplayTimingClient()
        // The archive reaches lights-out at 42:17, while the VOD reaches it at 3:48.
        val offset = client.syncOffsetForSessionStart(42 * 60_000L + 17_000L, 3 * 60_000L + 48_000L)
        client.setSyncOffset(offset)
        assertEquals(38 * 60_000L + 29_000L, client.archiveOffsetForVideoPosition(0L))
        assertEquals(39 * 60_000L + 29_000L, client.archiveOffsetForVideoPosition(60_000L))
    }

    @Test
    fun calibrationReturnsTimingMinusVideoPosition() {
        val client = ReplayTimingClient()
        assertEquals(1500L, client.calibratedOffset(10_000L, 11_500L))
    }

    @Test
    fun calibrationIsClampedToFiveMinutes() {
        val client = ReplayTimingClient()
        assertEquals(300_000L, client.calibratedOffset(0L, 500_000L))
        assertEquals(-300_000L, client.calibratedOffset(500_000L, 0L))
    }

    @Test
    fun nudgeChangesReplayOffset() {
        val client = ReplayTimingClient()
        client.setSyncOffset(1000L)
        client.nudge(250L)
        assertEquals(1250L, client.calibratedOffset(0L, 1250L))
    }
    @Test
    fun parsesCompressedPositionArchiveAndKeepsSessionOffset() {
        val json = """{"Position":[{"Entries":{"1":{"X":12.5,"Y":-7.25,"Z":1.0},"44":{"X":3.0,"Y":9.0}}}]}"""
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
        val stream = "00:00:01.000" + "\"" + encoded + "\"" + "\n"
        val snapshots = ReplayTimingClient().parsePositions(stream)

        assertEquals(1, snapshots.size)
        assertEquals(1000L, snapshots.single().offsetMs)
        assertEquals(2, snapshots.single().positions.size)
        val driver = snapshots.single().positions.first { it.number == "1" }
        assertEquals(12.5, driver.x, 0.0001)
        assertEquals(-7.25, driver.y, 0.0001)
        assertEquals(1.0, driver.z, 0.0001)
        assertTrue(driver.trail.isNotEmpty())
    }

    @Test
    fun parsesCompressedCarTelemetryForSpeedGearRpmThrottleAndBrake() {
        val json = """{"Entries":[{"Cars":{"1":{"Channels":{"0":11250,"2":318,"3":8,"4":100,"5":0}},"16":{"Channels":{"0":9800,"2":256,"3":7,"4":93,"5":0}}}}]}"""
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
        val stream = "00:00:01.000" + "\"" + encoded + "\"" + "\n"
        val snapshots = ReplayTimingClient().parseCarTelemetry(stream)

        assertEquals(1, snapshots.size)
        assertEquals(1000L, snapshots.single().offsetMs)
        val leader = snapshots.single().telemetry.first { it.driver == "1" }
        assertEquals(318, leader.speed)
        assertEquals(11250, leader.rpm)
        assertEquals(8, leader.gear)
        assertEquals(100, leader.throttle)
        assertEquals(0, leader.brake)
        val second = snapshots.single().telemetry.first { it.driver == "16" }
        assertEquals(256, second.speed)
        assertEquals(7, second.gear)
    }

    @Test
    fun ignoresMalformedCompressedCarTelemetryRecords() {
        assertTrue(ReplayTimingClient().parseCarTelemetry("not a timing record\n00:00:01.000\"" + "not-base64" + "\"").isEmpty())
    }

    @Test
    fun malformedPositionRecordsAreSkippedWithoutCrashing() {
        assertTrue(ReplayTimingClient().parsePositions("not a timing record" + "\n" + "00:00:01.000" + "\"" + "not-base64" + "\"").isEmpty())
    }

    @Test
    fun parsesArchivedSessionCircuitMetadata() {
        val stream = """00:00:00.000{"Name":"Qualifying 1","Type":"Qualifying","StartDate":"2026-10-10T08:00:00Z","Meeting":{"Name":"Singapore Grand Prix","Country":{"Name":"Singapore"},"Circuit":{"Key":61,"Name":"Marina Bay Street Circuit"}}}"""
        val info = ReplayTimingClient().parseSessionInfo(stream)
        assertEquals("Qualifying 1", info?.name)
        assertEquals("Singapore Grand Prix", info?.meeting)
        assertEquals("Singapore", info?.country)
        assertEquals("Marina Bay Street Circuit", info?.circuitName)
        assertEquals(61, info?.circuitKey)
        assertEquals(2026, info?.year)
    }

    @Test
    fun parsesExtrapolatedClockSnapshotsForReplayPlayhead() {
        val clocks = ReplayTimingClient().parseClockSnapshots(
            """00:00:01.000{"Remaining":"00:59:12"}
00:00:02.000{"Remaining":"00:59:11"}"""
        )
        assertEquals(2, clocks.size)
        assertEquals("00:59:12", clocks.first().clock)
        assertEquals("00:59:11", clocks.last().clock)
    }

    @Test
    fun parsesArchivedWeatherAndTrackStatusAtSessionOffset() {
        val weather = ReplayTimingClient().parseWeatherSnapshots(
            """00:00:01.000{"AirTemp":"29.8","TrackTemp":"39.7","Humidity":"75.7","WindSpeed":"4.3","WindDirection":"S","Pressure":"1010.0"}"""
        )
        assertEquals(1, weather.size)
        assertEquals("29.8", weather.single().weather.air)
        assertEquals("1010.0", weather.single().weather.pressure)
        val statuses = ReplayTimingClient().parseTrackStatusSnapshots(
            """00:00:02.000{"Status":"1","Message":"All Clear"}"""
        )
        assertEquals(2_000L, statuses.single().offsetMs)
        assertEquals("GREEN", statuses.single().trackStatus.label)
    }

    @Test
    fun parsesArchivedDriverNamesTeamColoursAndTlas() {
        val stream = """00:00:00.000{"1":{"RacingNumber":"1","FullName":"Max Verstappen","Tla":"VER","TeamName":"Red Bull Racing","TeamColour":"3671C6"},"16":{"RacingNumber":"16","FirstName":"Charles","LastName":"Leclerc","Tla":"LEC","TeamName":"Ferrari","TeamColour":"E8002D"}}"""
        val drivers = ReplayTimingClient().parseDriverInfo(stream)
        assertEquals(2, drivers.size)
        val leader = drivers.first { it.number == "1" }
        assertEquals("Max Verstappen", leader.name)
        assertEquals("VER", leader.acronym)
        assertEquals("Red Bull Racing", leader.team)
        assertEquals("3671C6", leader.teamColor)
        val second = drivers.first { it.number == "16" }
        assertEquals("Charles Leclerc", second.name)
        assertEquals("LEC", second.acronym)
    }

    @Test
    fun replayTimingDeltasMergeIntoPersistentDriverState() {
        val stream = """00:00:01.000{"Lines":{"1":{"Position":1,"Tla":"VER","GapToLeader":"LEADER","LastLapTime":{"Value":"1:32.100"}},"4":{"Position":2,"Tla":"NOR","GapToLeader":"+1.2"}}}
00:00:02.000{"Lines":{"1":{"LastLapTime":{"Value":"1:31.900"}},"4":{"GapToLeader":"+0.8"}}}
"""
        val snapshots = ReplayTimingClient().parse(stream)
        assertEquals(2, snapshots.size)
        assertEquals(2, snapshots[1].rows.size)
        val leader = snapshots[1].rows.first { it.driver == "VER" }
        val second = snapshots[1].rows.first { it.driver == "NOR" }
        assertEquals("1:31.900", leader.lastLap)
        assertEquals("+0.8", second.gap)
    }

}
