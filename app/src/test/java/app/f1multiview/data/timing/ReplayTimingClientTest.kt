package app.f1multiview.data.timing

import app.f1multiview.model.TrackDriverPosition

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.assertTrue

class ReplayTimingClientTest {
    private fun archiveHttpClient(timingData: String, timingStatus: Int = 200): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val (status, body) = when {
                url.endsWith("/Index.json") -> 200 to """{"Meetings":[{"Number":4,"Key":"2026/test-meeting","Sessions":[{"Name":"Race","Type":"Race","Path":"2026/test-meeting/race","Key":"Race"}]}]}"""
                url.endsWith("/TimingData.jsonStream") -> timingStatus to timingData
                url.contains("api.multiviewer.app") -> 404 to "not found"
                else -> 200 to "{}"
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status in 200..299) "OK" else "Failure")
                .body(body.toResponseBody("text/plain".toMediaType()))
                .build()
        }).build()

    @Test
    fun rejectsMissingReplayIdentifiersWithoutMakingRequests() = runBlocking {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requests++
            chain.proceed(chain.request())
        }).build()
        val result = ReplayTimingClient(http).load(0, 0, "Race")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, requests)
    }

    @Test
    fun successfulArchivePublishesRealTimingRowsAtFirstTimestamp() = runBlocking {
        val timing = """00:00:01.000{"Lines":{"1":{"Position":1,"Tla":"VER","GapToLeader":"LEADER","LastLapTime":{"Value":"1:32.100"}}}}"""
        val client = ReplayTimingClient(archiveHttpClient(timing))
        var callbackOffset: Long? = null
        val result = client.load(2026, 4, "Race", "Race") { callbackOffset = it }
        assertTrue(result.isSuccess)
        assertEquals(1_000L, callbackOffset)
        assertEquals(1, client.rowsAt(1_000L).size)
        assertEquals("VER", client.rowsAt(1_000L).single().driver)
    }

    @Test
    fun emptyTimingArchiveReturnsUnavailableInsteadOfSuccess() = runBlocking {
        val result = ReplayTimingClient(archiveHttpClient("")).load(2026, 4, "Race", "Race")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No parseable TimingData rows") == true)
    }

    @Test
    fun timingHttpFailureReturnsFailureInsteadOfLeavingLoaderPending() = runBlocking {
        val result = ReplayTimingClient(archiveHttpClient("service unavailable", timingStatus = 503))
            .load(2026, 4, "Race", "Race")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTP 503") == true)
    }
    @Test
    fun rejectsArchiveThatHasNoParseableTimingRows() {
        val failure = runCatching {
            ReplayTimingClient().requireTimingRows(emptyList(), 2026, 4, "Race")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("No parseable TimingData rows") == true)
        assertTrue(failure?.message?.contains("year=2026, meeting=4, session=Race") == true)
    }

    @Test
    fun archiveLookupUsesSessionRelativeMillisecondsNotAbsoluteUtcEpoch() {
        val client = ReplayTimingClient()
        assertEquals(0L, client.archiveOffsetForVideoPosition(0L))
        assertEquals(2_500L, client.archiveOffsetForVideoPosition(2_500L))
        client.setSyncOffset(750L)
        assertEquals(3_250L, client.archiveOffsetForVideoPosition(2_500L))
    }

    @Test
    fun replayClockExtrapolatesAtVideoSpeedBetweenSparseClockRecords() {
        assertEquals(3_600_000L, ReplayTimingClient().parseClockMillis("01:00:00"))
        assertEquals(60_000L, ReplayTimingClient().parseClockMillis("01:00"))
        assertEquals("00:59", ReplayTimingClient().formatClock(59_000L))
        assertEquals("1:00:00", ReplayTimingClient().formatClock(3_600_000L))
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
    fun replaySyncCalibrationStateDoesNotTreatRelativeNudgesAsVerified() {
        val client = ReplayTimingClient()
        assertFalse(client.isSyncCalibrated())
        client.nudge(1_000L)
        assertFalse(client.isSyncCalibrated())
        client.setSyncOffset(15_000L)
        assertTrue(client.isSyncCalibrated())
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
        val json = """{"Entries":[{"Cars":{"1":{"Channels":{"0":11250,"2":318,"3":8,"4":100,"5":0,"45":8}},"16":{"Channels":{"0":9800,"2":256,"3":7,"4":93,"5":0,"45":0}}}}]}"""
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
        assertEquals(8, leader.drs)
        val second = snapshots.single().telemetry.first { it.driver == "16" }
        assertEquals(256, second.speed)
        assertEquals(7, second.gear)
        assertEquals(0, second.drs)
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
    fun exactArchiveSessionNameWinsOverBroadTypeMatch() {
        val candidates = listOf(
            ReplayArchiveSession("Sprint Qualifying", "Qualifying", "2026/meeting/sprint-qualifying"),
            ReplayArchiveSession("Qualifying", "Qualifying", "2026/meeting/qualifying")
        )
        val result = resolveReplayArchiveSession(candidates, "Sprint Qualifying")
        assertEquals("2026/meeting/sprint-qualifying", result.session.path)
        assertEquals("exact-title-name", result.reason)
    }

    @Test
    fun requestedSessionTitleDisambiguatesSharedArchiveTypes() {
        val candidates = listOf(
            ReplayArchiveSession("Practice 1", "Practice", "p1"),
            ReplayArchiveSession("Practice 2", "Practice", "p2"),
            ReplayArchiveSession("Practice 3", "Practice", "p3")
        )
        val result = resolveReplayArchiveSession(
            candidates,
            requestedSessionType = "Practice",
            requestedSessionTitle = "2026 Singapore Grand Prix - Practice 2"
        )
        assertEquals("p2", result.session.path)
        assertEquals("title-suffix-name", result.reason)
    }

    @Test
    fun titleEvidenceCanPreferSprintQualifyingOverBroadQualifyingType() {
        val candidates = listOf(
            ReplayArchiveSession("Sprint Qualifying", "Qualifying", "sprint-qualifying"),
            ReplayArchiveSession("Qualifying", "Qualifying", "qualifying")
        )
        val result = resolveReplayArchiveSession(
            candidates,
            requestedSessionType = "Qualifying",
            requestedSessionTitle = "Singapore Grand Prix - Sprint Qualifying"
        )
        assertEquals("sprint-qualifying", result.session.path)
    }

    @Test
    fun resolvesPracticeByExactNameRatherThanSharedSessionType() {
        val candidates = listOf(
            ReplayArchiveSession("Practice 1", "Practice", "p1"),
            ReplayArchiveSession("Practice 2", "Practice", "p2"),
            ReplayArchiveSession("Practice 3", "Practice", "p3")
        )
        assertEquals("p2", resolveReplayArchiveSession(candidates, "Practice 2").session.path)
    }

    @Test
    fun rejectsAmbiguousArchiveSessionTypesInsteadOfChoosingFirst() {
        val candidates = listOf(
            ReplayArchiveSession("Practice 1", "Practice", "p1"),
            ReplayArchiveSession("Practice 2", "Practice", "p2")
        )
        val failure = runCatching { resolveReplayArchiveSession(candidates, "Practice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("Ambiguous") == true)
    }

    @Test
    fun rejectsMissingArchiveSessionMatches() {
        val failure = runCatching {
            resolveReplayArchiveSession(listOf(ReplayArchiveSession("Race", "Race", "race")), "Sprint")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("No archive session matches") == true)
    }

    @Test
    fun sparseReplayPositionFramesRetainUnchangedDriversWithTheirRealTimestamps() {
        fun encode(raw: String): String {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            val bytes = ByteArrayOutputStream()
            try {
                deflater.setInput(raw.toByteArray(Charsets.UTF_8))
                deflater.finish()
                val buffer = ByteArray(512)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    bytes.write(buffer, 0, count)
                }
            } finally {
                deflater.end()
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray())
        }
        val first = encode("""{"Position":[{"Entries":{"1":{"X":10.0,"Y":1.0},"44":{"X":20.0,"Y":2.0}}}]}""")
        val later = encode("""{"Position":[{"Entries":{"1":{"X":60.0,"Y":6.0}}}]}""")
        val stream = listOf(
            "00:00:01.000\"$first\"",
            "00:00:02.000\"broken\"",
            "00:00:03.000\"broken\"",
            "00:00:04.000\"broken\"",
            "00:00:05.000\"broken\"",
            "00:00:06.000\"$later\""
        ).joinToString("\n")

        val snapshots = ReplayTimingClient().parsePositions(stream)

        assertEquals(2, snapshots.size)
        val secondFrame = snapshots.last()
        assertEquals(2, secondFrame.positions.size)
        val updated = secondFrame.positions.first { it.number == "1" }
        val unchanged = secondFrame.positions.first { it.number == "44" }
        assertEquals(60.0, updated.x, 0.0001)
        assertEquals(6_000L, updated.updatedAtMs)
        assertEquals(20.0, unchanged.x, 0.0001)
        assertEquals(1_000L, unchanged.updatedAtMs)
    }
    @Test
    fun replayPositionsInterpolateAtTheExactVideoPlayhead() {
        val first = TrackDriverPosition(
            number = "1", x = 0.0, y = 10.0, updatedAtMs = 1_000L
        )
        val second = TrackDriverPosition(
            number = "1", x = 100.0, y = 30.0, updatedAtMs = 1_100L
        )
        val snapshots = listOf(
            ReplayPositionSnapshot(1_000L, listOf(first)),
            ReplayPositionSnapshot(1_100L, listOf(second))
        )

        val positions = interpolateReplayPositionSnapshots(snapshots, 1_050L)

        assertEquals(1, positions.size)
        assertEquals(50.0, positions.single().x, 0.0001)
        assertEquals(20.0, positions.single().y, 0.0001)
        // Keep the timestamp of the latest real source sample behind the interpolated point.
        assertEquals(1_000L, positions.single().updatedAtMs)
    }

    @Test
    fun replayPositionsBeforeFirstSampleAreUnavailableRatherThanFutureProjected() {
        val future = TrackDriverPosition(number = "1", x = 50.0, y = 12.0, updatedAtMs = 5_000L)
        val positions = interpolateReplayPositionSnapshots(
            listOf(ReplayPositionSnapshot(5_000L, listOf(future))),
            targetOffsetMs = 2_000L
        )
        assertTrue(positions.isEmpty())
    }

    @Test
    fun replayPositionsDoNotInterpolateAcrossLargeMissingDataGaps() {
        val first = TrackDriverPosition(number = "1", x = 0.0, y = 10.0, updatedAtMs = 1_000L)
        val second = TrackDriverPosition(number = "1", x = 100.0, y = 30.0, updatedAtMs = 5_000L)
        val positions = interpolateReplayPositionSnapshots(
            listOf(ReplayPositionSnapshot(1_000L, listOf(first)), ReplayPositionSnapshot(5_000L, listOf(second))),
            3_000L
        )
        assertEquals(0.0, positions.single().x, 0.0)
        assertEquals(10.0, positions.single().y, 0.0)
    }

    @Test
    fun archivedTimingAppDataRetainsCompoundPitAndStintState() {
        val stream = """00:00:01.000{"Lines":{"1":{"Stints":{"0":{"Compound":"SOFT"},"1":{"Compound":"MEDIUM"}},"NumberOfPitStops":1,"InPit":true,"Stopped":false}}}
00:00:02.000{"Lines":{"1":{"Stints":{"1":{"Compound":"HARD"}},"NumberOfPitStops":2,"InPit":false,"Stopped":false}}}"""
        val snapshots = ReplayTimingClient().parseTimingAppData(stream)
        assertEquals(2, snapshots.size)
        val earlier = snapshots[0].stints.getValue("1")
        val latest = snapshots[1].stints.getValue("1")
        assertEquals("MEDIUM", earlier.compound)
        assertEquals(2, earlier.stintNumber)
        assertEquals(1, earlier.pitStops)
        assertTrue(earlier.inPit)
        assertEquals("HARD", latest.compound)
        assertEquals(2, latest.pitStops)
        assertEquals(2, latest.stintNumber)
        assertFalse(latest.inPit)
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
