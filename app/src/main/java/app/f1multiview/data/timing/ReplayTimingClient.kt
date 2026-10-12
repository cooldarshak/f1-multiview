package app.f1multiview.data.timing
import app.f1multiview.model.TimingRow
import app.f1multiview.model.DriverTelemetry
import app.f1multiview.model.DriverInfo
import app.f1multiview.model.LiveSessionInfo
import app.f1multiview.model.TrackStatusInfo
import app.f1multiview.model.TimingWeather
import app.f1multiview.model.TrackDriverPosition
import app.f1multiview.model.TrackPoint
import app.f1multiview.media.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader
import java.util.zip.Inflater
import java.util.Base64
import org.json.JSONArray

data class ReplayTimingSnapshot(val offsetMs:Long,val rows:List<TimingRow>)
data class ReplayPositionSnapshot(val offsetMs:Long,val positions:List<TrackDriverPosition>)
data class ReplayTelemetrySnapshot(val offsetMs:Long,val telemetry:List<DriverTelemetry>)
internal data class ReplayStint(val compound:String,val pitStops:Int,val inPit:Boolean,val stopped:Boolean,val stintNumber:Int,val stintLaps:Int?)
internal data class ReplayStintSnapshot(val offsetMs:Long,val stints:Map<String,ReplayStint>)
data class ReplayWeatherSnapshot(val offsetMs:Long,val weather:TimingWeather)
data class ReplayTrackStatusSnapshot(val offsetMs:Long,val trackStatus:TrackStatusInfo)
data class ReplayLapSnapshot(val offsetMs:Long,val current:Int,val total:Int)
data class ReplayClockSnapshot(val offsetMs:Long,val clock:String,val extrapolating:Boolean,val utc:String?=null)
data class ReplayMetadataState(
    val sessionInfo:LiveSessionInfo?,
    val weather:TimingWeather,
    val trackStatus:TrackStatusInfo,
    val currentLap:Int,
    val totalLaps:Int,
    val sessionClock:String
)
data class ReplaySyncData(val sessionStartMs:Long,val channelDiffs:Map<String,Long>)
internal data class ReplayArchiveSession(val name:String,val type:String,val path:String,val key:String="")
internal data class ReplaySessionResolution(val session:ReplayArchiveSession,val reason:String)

private data class ReplayArchiveRecord(val offsetMs: Long, val payload: String)

private val replayArchiveTimestampPrefix = Regex("""^(\d{1,2}:\d{2}:\d{2}[:.]\d{1,9})""")
private val replayArchiveTimestampValue = Regex("""^(\d{1,2}):(\d{2}):(\d{2})[:.](\d{1,9})$""")

private fun parseReplayArchiveOffset(value: String): Long? {
    val match = replayArchiveTimestampValue.matchEntire(value.trim()) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: return null
    val minutes = match.groupValues[2].toLongOrNull() ?: return null
    val seconds = match.groupValues[3].toLongOrNull() ?: return null
    val millis = match.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: return null
    if (minutes !in 0..59 || seconds !in 0..59) return null
    return ((hours * 60L + minutes) * 60L + seconds) * 1000L + millis
}

private fun parseReplayArchiveRecord(line: String): ReplayArchiveRecord? {
    val cleaned = line.trimStart('\uFEFF', ' ', '\t')
    val timestamp = replayArchiveTimestampPrefix.find(cleaned) ?: return null
    if (timestamp.range.first != 0) return null
    val offset = parseReplayArchiveOffset(timestamp.groupValues[1]) ?: return null
    val payload = cleaned.substring(timestamp.range.last + 1).trimStart(' ', '\t', ',', ':')
    if (payload.isBlank()) return null
    return ReplayArchiveRecord(offset, payload)
}

private fun replayArchiveJsonObject(line: String): JSONObject? {
    val payload = parseReplayArchiveRecord(line)?.payload ?: run {
        val cleaned = line.trimStart('\uFEFF', ' ', '\t')
        val brace = cleaned.indexOf('{')
        if (brace < 0) return null
        cleaned.substring(brace)
    }
    return runCatching { org.json.JSONTokener(payload).nextValue() as? JSONObject }.getOrNull()
}

internal fun resolveReplayArchiveSession(
    candidates: List<ReplayArchiveSession>,
    requestedSessionType: String,
    requestedSessionTitle: String = requestedSessionType
): ReplaySessionResolution {
    fun normalize(value: String) =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    fun canonical(value: String): String {
        val normalized = normalize(value)
        return when {
            normalized.contains("sprint") && normalized.contains("qual") -> "sprint qualifying"
            normalized.contains("practice 1") -> "practice 1"
            normalized.contains("practice 2") -> "practice 2"
            normalized.contains("practice 3") -> "practice 3"
            normalized.contains("sprint") && normalized.contains("race") -> "sprint"
            normalized.contains("sprint") -> "sprint"
            normalized.contains("qual") -> "qualifying"
            normalized.contains("race") -> "race"
            else -> normalized
        }
    }

    val wantedType = canonical(requestedSessionType)
    val title = normalize(requestedSessionTitle)
    val scored = candidates.mapNotNull { candidate ->
        val name = normalize(candidate.name)
        val type = canonical(candidate.type)
        if (name.isBlank() || candidate.path.isBlank()) return@mapNotNull null
        // Keep score bands disjoint: an exact title must always outrank a suffix candidate
        // (e.g. "Sprint Qualifying" vs "Qualifying"), regardless of candidate-name length.
        val (score, reason) = when {
            title.isNotBlank() && title == name -> 4_000 to "exact-title-name"
            title.isNotBlank() && title.endsWith(name) -> 3_000 + name.length.coerceAtMost(20) to "title-suffix-name"
            title.isNotBlank() && title.contains(name) -> 2_000 + name.length.coerceAtMost(20) to "title-contains-name"
            name == wantedType -> 1_500 to "exact-name"
            type == wantedType -> 1_000 to "exact-type"
            name.contains(wantedType) || wantedType.contains(name) -> 500 + name.length.coerceAtMost(20) to "name-containment"
            else -> 0 to "no-match"
        }
        Triple(candidate, score, reason)
    }

    val bestScore = scored.maxOfOrNull { it.second } ?: 0
    if (bestScore <= 0) {
        error("No archive session matches type='$requestedSessionType' title='$requestedSessionTitle'")
    }
    val best = scored.filter { it.second == bestScore }
    if (best.size != 1) {
        error("Ambiguous archive session match type='$requestedSessionType' title='$requestedSessionTitle' score=$bestScore candidates=${best.joinToString { it.first.name }}")
    }
    val selected = best.single()
    return ReplaySessionResolution(selected.first, selected.third)
}

internal fun interpolateReplayPositionSnapshots(
    snapshots: List<ReplayPositionSnapshot>,
    targetOffsetMs: Long,
    maxGapMs: Long = 1_500L
): List<TrackDriverPosition> {
    if (snapshots.isEmpty()) return emptyList()
    val ordered = snapshots.sortedBy { it.offsetMs }
    val before = ordered.lastOrNull { it.offsetMs <= targetOffsetMs } ?: return emptyList()
    val after = ordered.firstOrNull { it.offsetMs > targetOffsetMs } ?: return before.positions
    val gap = after.offsetMs - before.offsetMs
    if (gap !in 1L..maxGapMs) return before.positions

    val fraction = ((targetOffsetMs - before.offsetMs).toDouble() / gap.toDouble()).coerceIn(0.0, 1.0)
    val nextByNumber = after.positions.associateBy { it.number }
    return before.positions.map { current ->
        val next = nextByNumber[current.number] ?: return@map current
        current.copy(
            x = current.x + (next.x - current.x) * fraction,
            y = current.y + (next.y - current.y) * fraction,
            z = current.z + (next.z - current.z) * fraction,
            previousX = current.x,
            previousY = current.y,
            previousUpdatedAtMs = before.offsetMs,
            updatedAtMs = before.offsetMs
        )
    }
}

class ReplayTimingClient(private val http: OkHttpClient = OkHttpClient()) {
    fun clear() {
        snapshots=emptyList(); positionSnapshots=emptyList(); telemetrySnapshots=emptyList(); stintSnapshots=emptyList()
        archivedDriverInfo=emptyList(); archivedSessionInfo=null; weatherSnapshots=emptyList(); trackStatusSnapshots=emptyList()
        lapSnapshots=emptyList(); clockSnapshots=emptyList(); syncOffsetMs=0L; syncCalibrated=false; syncData=ReplaySyncData(0L, emptyMap())
    }
    /**
     * Archive endpoints can return a valid HTTP response whose lines do not match the parser's
     * supported TimingData shape. Do not report the replay timing service as ready unless it
     * produced actual timestamped driver rows.
     */
    internal fun requireTimingRows(
        parsed: List<ReplayTimingSnapshot>,
        year: Int,
        meetingNumber: Int,
        sessionType: String
    ) {
        if (parsed.isEmpty()) {
            throw IllegalStateException(
                "No parseable TimingData rows in F1 archive " +
                    "(year=$year, meeting=$meetingNumber, session=$sessionType)"
            )
        }
    }

    private var snapshots:List<ReplayTimingSnapshot> = emptyList()
    private var positionSnapshots:List<ReplayPositionSnapshot> = emptyList()
    private var telemetrySnapshots:List<ReplayTelemetrySnapshot> = emptyList()
    private var stintSnapshots:List<ReplayStintSnapshot> = emptyList()
    private var archivedDriverInfo:List<DriverInfo> = emptyList()
    private var archivedSessionInfo:LiveSessionInfo? = null
    private var weatherSnapshots:List<ReplayWeatherSnapshot> = emptyList()
    private var trackStatusSnapshots:List<ReplayTrackStatusSnapshot> = emptyList()
    private var lapSnapshots:List<ReplayLapSnapshot> = emptyList()
    private var clockSnapshots:List<ReplayClockSnapshot> = emptyList()
    private var syncOffsetMs:Long = 0L
    private var syncCalibrated:Boolean = false
    private var syncData = ReplaySyncData(0L, emptyMap())
    suspend fun load(
        year: Int,
        meetingNumber: Int,
        sessionType: String,
        sessionTitle: String = sessionType,
        onTimingRowsAvailable: suspend (firstVideoPositionMs: Long) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (year <= 0 || meetingNumber <= 0) {
            val failure = IllegalArgumentException("Replay timing requires positive year and meeting number (year=$year, meeting=$meetingNumber)")
            AppLogger.w("ReplayTiming", "REPLAY_LOAD_REJECTED reason=missing_identifier year=$year meeting=$meetingNumber")
            return@withContext Result.failure(failure)
        }
        clear()
        try {
            val index=getJson("https://livetiming.formula1.com/static/$year/Index.json")
            val meetings=index.optJSONArray("Meetings") ?: error("Timing archive has no meetings")
            val meeting=(0 until meetings.length()).map{meetings.getJSONObject(it)}.firstOrNull{it.optInt("Number",-1)==meetingNumber} ?: error("Timing archive meeting not found")
            val sessions=meeting.optJSONArray("Sessions") ?: error("Timing archive has no sessions")
            val candidates=(0 until sessions.length()).mapNotNull { i ->
                val item=sessions.optJSONObject(i) ?: return@mapNotNull null
                val name=item.optString("Name").trim()
                val type=item.optString("Type").trim()
                val path=item.optString("Path").trim('/')
                if (name.isBlank() || path.isBlank()) null else ReplayArchiveSession(name,type,path,item.optString("Key"))
            }
            AppLogger.i("ReplayTiming", "REPLAY_SESSION_CANDIDATES year=$year meeting=$meetingNumber candidates=${candidates.joinToString { it.name + ":" + it.type }}")
            val resolution=resolveReplayArchiveSession(candidates,sessionType,sessionTitle)
            val session=resolution.session
            AppLogger.i("ReplayTiming", "REPLAY_SESSION_SELECTED name=${session.name} type=${session.type} path=${session.path} reason=${resolution.reason}")
            val path=session.path
            // TimingData keys drivers by racing number. Load the archive's driver identity map
            // first; relying on Tla embedded in TimingData silently drops every row when absent.
            archivedDriverInfo=loadOptionalArchive("DriverList.jsonStream", emptyList()) {
                parseDriverInfo(getText("https://livetiming.formula1.com/static/$path/DriverList.jsonStream"))
            }
            AppLogger.i("ReplayTiming", "REPLAY_DRIVER_LIST_PARSED count=" + archivedDriverInfo.size)
            snapshots=parse(
                getText("https://livetiming.formula1.com/static/$path/TimingData.jsonStream"),
                archivedDriverInfo
            )
            AppLogger.i(
                "ReplayTiming",
                "REPLAY_TIMING_PARSE_COMPLETE session=${session.name} snapshots=${snapshots.size} rowsAtFirst=${snapshots.firstOrNull()?.rows?.size ?: 0}"
            )
            requireTimingRows(snapshots, year, meetingNumber, sessionType)
            // Publish usable timing rows as soon as the primary TimingData archive is parsed.
            // CarData and Position archives are large and must not block the timing tower from
            // rendering rows already available for this replay.
            val firstTimingOffsetMs = snapshots.first().offsetMs.coerceAtLeast(0L)
            onTimingRowsAvailable(firstTimingOffsetMs)
            archivedSessionInfo=loadOptionalArchive<LiveSessionInfo?>("SessionInfo.jsonStream", null) { parseSessionInfo(getText("https://livetiming.formula1.com/static/$path/SessionInfo.jsonStream")) }
            weatherSnapshots=loadOptionalArchive("WeatherData.jsonStream", emptyList()) { parseWeatherSnapshots(getText("https://livetiming.formula1.com/static/$path/WeatherData.jsonStream")) }
            trackStatusSnapshots=loadOptionalArchive("TrackStatus.jsonStream", emptyList()) { parseTrackStatusSnapshots(getText("https://livetiming.formula1.com/static/$path/TrackStatus.jsonStream")) }
            lapSnapshots=loadOptionalArchive("LapCount.jsonStream", emptyList()) { parseLapSnapshots(getText("https://livetiming.formula1.com/static/$path/LapCount.jsonStream")) }
            clockSnapshots=loadOptionalArchive("ExtrapolatedClock.jsonStream", emptyList()) { parseClockSnapshots(getText("https://livetiming.formula1.com/static/$path/ExtrapolatedClock.jsonStream")) }
            stintSnapshots=loadOptionalArchive("TimingAppData.jsonStream", emptyList()) { parseTimingAppData(getText("https://livetiming.formula1.com/static/$path/TimingAppData.jsonStream")) }
            // These two compressed feeds are needed to keep car telemetry and map markers tied to
            // the replay media clock, including backwards seeks. No live values are mixed in.
            telemetrySnapshots=loadOptionalArchive("CarData.z.jsonStream", emptyList()) { parseCarTelemetry(getText("https://livetiming.formula1.com/static/$path/CarData.z.jsonStream")) }
            positionSnapshots=loadOptionalArchive("Position.z.jsonStream", emptyList()) { parsePositions(getText("https://livetiming.formula1.com/static/$path/Position.z.jsonStream")) }
            val curatedSync=loadCuratedSync(meeting.optString("Key"),session.key)
            syncData=curatedSync ?: ReplaySyncData(0L, emptyMap())
            // The MultiViewer sync endpoint's session_start is NOT an epoch timestamp. It is the
            // video position (seconds) at the official session start/lights-out. The archive's
            // ExtrapolatedClock has a relative offset where Extrapolating first becomes true (K).
            // Convert video position to archive position with: archive = video + K - session_start.
            // This is what keeps tower, sectors, car telemetry and map markers on the video playhead.
            val clockStart=clockSnapshots.firstOrNull { it.extrapolating }?.offsetMs
            syncCalibrated = curatedSync != null && clockStart != null
            syncOffsetMs=if(syncCalibrated) {
                syncOffsetForSessionStart(clockStart!!,curatedSync!!.sessionStartMs)
            } else {
                // Zero is only a fallback origin, never proof of video/archive alignment.
                0L
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLogger.w(
                "ReplayTiming",
                "REPLAY_LOAD_FAILED year=$year meeting=$meetingNumber reason=${failure.javaClass.simpleName} message=${failure.message}"
            )
            Result.failure(failure)
        }
    }
    internal fun archiveOffsetForVideoPosition(videoPositionMs:Long):Long =
        (videoPositionMs + syncOffsetMs).coerceAtLeast(0L)

    fun rowsAt(videoPositionMs:Long):List<TimingRow> {
        val target=archiveOffsetForVideoPosition(videoPositionMs)
        val rows=findSnapshot(snapshots,target){it.offsetMs}?.rows ?: return emptyList()
        val stints=findSnapshot(stintSnapshots,target){it.offsetMs}?.stints.orEmpty()
        return rows.map { row ->
            val stint=stints[row.driverNumber]
            val car = findSnapshot(telemetrySnapshots, target) { it.offsetMs }?.telemetry?.firstOrNull { it.driver == row.driverNumber }
            row.copy(
                tyre=stint?.compound?.takeIf{it.isNotBlank()} ?: row.tyre,
                pitStops=stint?.pitStops ?: row.pitStops,
                lastLap=row.lastLap,
                inPit=stint?.inPit ?: row.inPit,
                stopped=stint?.stopped ?: row.stopped,
                stintNumber=stint?.stintNumber?.takeIf { it > 0 } ?: row.stintNumber,
                stintLaps=stint?.stintLaps ?: row.stintLaps,
                gear=car?.gear ?: row.gear,
                rpm=car?.rpm ?: row.rpm,
                speed=car?.speed?.takeIf { it > 0 }?.toString() ?: row.speed,
                drsActive=car?.drs?.let { it >= 8 }
            )
        }
    }

    fun telemetryAt(videoPositionMs:Long):List<DriverTelemetry> {
        val target=archiveOffsetForVideoPosition(videoPositionMs)
        val snapshot=findSnapshot(telemetrySnapshots,target){it.offsetMs} ?: return emptyList()
        val rows=rowsAt(videoPositionMs).associateBy{it.driverNumber}
        return snapshot.telemetry.map { item ->
            val row=rows[item.driver]
            item.copy(lap=row?.lap ?: item.lap,lapTime=row?.lastLap ?: item.lapTime)
        }
    }

    fun positionsAt(videoPositionMs:Long):List<TrackDriverPosition> {
        val target=archiveOffsetForVideoPosition(videoPositionMs)
        return interpolateReplayPositionSnapshots(positionSnapshots, target)
    }

    fun positionSnapshotCount():Int = positionSnapshots.size
    fun telemetrySnapshotCount():Int = telemetrySnapshots.size
    fun driverInfo():List<DriverInfo> = archivedDriverInfo
    fun sessionInfo():LiveSessionInfo? = archivedSessionInfo

    fun metadataAt(videoPositionMs:Long):ReplayMetadataState {
        val target=archiveOffsetForVideoPosition(videoPositionMs)
        return ReplayMetadataState(
            sessionInfo=archivedSessionInfo,
            weather=findSnapshot(weatherSnapshots,target){it.offsetMs}?.weather ?: TimingWeather(),
            trackStatus=findSnapshot(trackStatusSnapshots,target){it.offsetMs}?.trackStatus ?: TrackStatusInfo(),
            currentLap=findSnapshot(lapSnapshots,target){it.offsetMs}?.current ?: 0,
            totalLaps=findSnapshot(lapSnapshots,target){it.offsetMs}?.total ?: 0,
            sessionClock=clockAt(target)
        )
    }

    internal fun syncOffsetForSessionStart(clockStartOffsetMs:Long, videoSecondsToSessionStartMs:Long):Long =
        clockStartOffsetMs-videoSecondsToSessionStartMs

    fun setSyncOffset(offsetMs:Long){ syncOffsetMs=offsetMs; syncCalibrated=true }

    fun nudge(deltaMs:Long){ syncOffsetMs += deltaMs; syncCalibrated=false }

    fun isSyncCalibrated():Boolean = syncCalibrated
    fun currentSyncOffsetMs():Long = syncOffsetMs

    fun calibratedOffset(videoPositionMs:Long, timingPositionMs:Long):Long =
        (timingPositionMs-videoPositionMs).coerceIn(-300_000L,300_000L)

    fun snapshotCount():Int = snapshots.size
    fun isLoaded()=snapshots.isNotEmpty()
    fun sync():ReplaySyncData=syncData
    internal fun parseSessionInfo(text:String):LiveSessionInfo? {
        var parsed: LiveSessionInfo? = null
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            val root=replayArchiveJsonObject(line) ?: return@forEachLine
            val info=root.optJSONObject("SessionInfo") ?: root
            val meeting=info.optJSONObject("Meeting") ?: JSONObject()
            val circuit=meeting.optJSONObject("Circuit") ?: info.optJSONObject("Circuit") ?: JSONObject()
            val country=meeting.optJSONObject("Country") ?: JSONObject()
            val keyRaw=circuit.opt("Key") ?: circuit.opt("CircuitKey") ?: meeting.opt("CircuitKey")
            val key=when(keyRaw) {
                is Number->keyRaw.toInt().takeIf{it>0}
                is String->keyRaw.toIntOrNull()?.takeIf{it>0}
                else->null
            } ?: meeting.optInt("CircuitKey",0).takeIf{it>0}
            val start=info.optString("StartDate")
            val end=info.optString("EndDate")
            val year=start.takeIf{it.length>=4}?.take(4)?.toIntOrNull()
                ?: end.takeIf{it.length>=4}?.take(4)?.toIntOrNull()
            val name=info.optString("Name").ifBlank{info.optString("SessionType")}
            val meetingName=info.optString("MeetingName").ifBlank{meeting.optString("Name")}.ifBlank{name}
            val circuitName=circuit.optString("Name").ifBlank{circuit.optString("ShortName")}.ifBlank{meeting.optString("Location")}
            if(name.isNotBlank()||meetingName.isNotBlank()||key!=null) {
                parsed = LiveSessionInfo(
                    name=name.ifBlank{"-"},
                    meeting=meetingName.ifBlank{"-"},
                    country=country.optString("Name").ifBlank{country.optString("Code")}.ifBlank{info.optString("Country")}.ifBlank{"-"},
                    sessionType=info.optString("Type").ifBlank{info.optString("SessionType")}.ifBlank{"-"},
                    status=info.optString("Status").ifBlank{"-"},
                    circuitKey=key,year=year,circuitName=circuitName
                )
                return@forEachLine
            }
        }
        return parsed
    }

    internal fun parseWeatherSnapshots(text:String):List<ReplayWeatherSnapshot> {
        val merged=JSONObject()
        val out=ArrayList<ReplayWeatherSnapshot>()
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            val record=parseReplayArchiveRecord(line) ?: return@forEachLine
            val offset=record.offsetMs
            val root=runCatching{org.json.JSONTokener(record.payload).nextValue() as? JSONObject}.getOrNull() ?: return@forEachLine
            val delta=root.optJSONObject("WeatherData") ?: root
            mergeObject(merged,delta)
            fun value(vararg keys:String):String=keys.firstNotNullOfOrNull{merged.optString(it).takeIf{v->v.isNotBlank()&&v!="null"}} ?: "-"
            out+=ReplayWeatherSnapshot(offset,TimingWeather(
                air=value("AirTemp","AirTemperature"),
                track=value("TrackTemp","TrackTemperature"),
                humidity=value("Humidity"),
                wind=value("WindSpeed","Wind"),
                rainfall=value("Rainfall","RainfallIntensity"),
                windDirection=value("WindDirection"),
                pressure=value("Pressure","PressureMbar","AirPressure")
            ))
        }
        return out
    }

    internal fun parseTrackStatusSnapshots(text:String):List<ReplayTrackStatusSnapshot> {
        val out=ArrayList<ReplayTrackStatusSnapshot>()
        var latest=TrackStatusInfo()
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            val record=parseReplayArchiveRecord(line) ?: return@forEachLine
            val offset=record.offsetMs
            val root=runCatching{org.json.JSONTokener(record.payload).nextValue() as? JSONObject}.getOrNull() ?: return@forEachLine
            val data=root.optJSONObject("TrackStatus") ?: root
            val raw=data.optString("Status")
            val code=raw.toIntOrNull() ?: data.optInt("Status",latest.code)
            val label=when(code){1->"GREEN";2->"YELLOW";4->"SAFETY CAR";5->"RED";6->"VSC";7->"VSC ENDING";else->"STATUS $code"}
            latest=TrackStatusInfo(code,label,data.optString("Message").ifBlank{latest.message})
            out+=ReplayTrackStatusSnapshot(offset,latest)
        }
        return out
    }

    private fun clockAt(targetMs:Long):String {
        val snapshot=findSnapshot(clockSnapshots,targetMs){it.offsetMs} ?: return "-"
        if(!snapshot.extrapolating)return snapshot.clock
        val base=parseClockMillis(snapshot.clock) ?: return snapshot.clock
        val remaining=(base-(targetMs-snapshot.offsetMs)).coerceAtLeast(0L)
        return formatClock(remaining)
    }

    internal fun parseClockMillis(value:String):Long? {
        val parts=value.trim().split(":")
        if(parts.size !in 2..3)return null
        val seconds=parts.last().toDoubleOrNull() ?: return null
        val minutes=parts[parts.size-2].toLongOrNull() ?: return null
        val hours=if(parts.size==3)parts[0].toLongOrNull() ?: return null else 0L
        return ((hours*60+minutes)*60_000L+seconds*1000.0).toLong()
    }

    internal fun formatClock(valueMs:Long):String {
        val seconds=(valueMs.coerceAtLeast(0L)/1000L).toInt()
        val hours=seconds/3600
        val minutes=(seconds%3600)/60
        val secs=seconds%60
        return if(hours>0) "%d:%02d:%02d".format(hours,minutes,secs) else "%02d:%02d".format(minutes,secs)
    }

    internal fun parseClockSnapshots(text:String):List<ReplayClockSnapshot> {
        val out=ArrayList<ReplayClockSnapshot>()
        var latest="-"
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            val record=parseReplayArchiveRecord(line) ?: return@forEachLine
            val offset=record.offsetMs
            val root=runCatching{org.json.JSONTokener(record.payload).nextValue() as? JSONObject}.getOrNull() ?: return@forEachLine
            val data=root.optJSONObject("ExtrapolatedClock") ?: root
            val remaining=data.optString("Remaining").ifBlank{data.optString("Clock").ifBlank{data.optString("Time")}}
            if(remaining.isNotBlank() && remaining!="null")latest=remaining
            val extrapolating=data.optBoolean("Extrapolating",false)
            val utc=data.optString("Utc").takeIf{it.isNotBlank()&&it!="null"}
            if(latest!="-")out+=ReplayClockSnapshot(offset,latest,extrapolating,utc)
        }
        return out
    }

    internal fun parseLapSnapshots(text:String):List<ReplayLapSnapshot> {
        val out=ArrayList<ReplayLapSnapshot>()
        var current=0;var total=0
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            val record=parseReplayArchiveRecord(line) ?: return@forEachLine
            val offset=record.offsetMs
            val root=runCatching{org.json.JSONTokener(record.payload).nextValue() as? JSONObject}.getOrNull() ?: return@forEachLine
            val data=root.optJSONObject("LapCount") ?: root
            current=data.optInt("CurrentLap",data.optInt("CurrentLapNumber",current))
            total=data.optInt("TotalLaps",total)
            if(current>0||total>0)out+=ReplayLapSnapshot(offset,current,total)
        }
        return out
    }

    internal fun parseDriverInfo(text:String):List<DriverInfo> {
        val result=linkedMapOf<String,DriverInfo>()
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            val body=replayArchiveJsonObject(line) ?: return@forEachLine
            val root=body.optJSONObject("DriverList") ?: body.optJSONObject("Drivers") ?: body.optJSONObject("Lines") ?: body
            val keys=root.keys()
            while(keys.hasNext()){
                val key=keys.next()
                val item=root.optJSONObject(key) ?: continue
                val number=item.optString("RacingNumber").ifBlank{key}
                val name=item.optString("FullName").ifBlank{
                    listOf(item.optString("FirstName"),item.optString("LastName")).filter(String::isNotBlank).joinToString(" ")
                }.ifBlank{item.optString("BroadcastName")}.ifBlank{number}
                val acronym=item.optString("Tla").ifBlank{item.optString("ShortName")}.ifBlank{number}
                val team=item.optString("TeamName").ifBlank{item.optString("Team")}.ifBlank{"-"}
                val color=item.optString("TeamColour").ifBlank{item.optString("TeamColor")}.removePrefix("#").ifBlank{"FFFFFF"}
                // F1OpenViewer/FastF1 fall back to DriverList.Line when a TimingData
                // delta does not yet carry Position. Preserve that source value explicitly.
                val line = item.optInt("Line", 0).takeIf { it > 0 }
                if(number.isNotBlank())result[number]=DriverInfo(number,name,acronym,team,color,line)
            }
            if(result.isNotEmpty())return@forEachLine
        }
        return result.values.sortedWith(compareBy<DriverInfo>{it.number.toIntOrNull()?:Int.MAX_VALUE}.thenBy{it.number})
    }

    internal fun parse(text:String, driverInfo: List<DriverInfo> = emptyList()):List<ReplayTimingSnapshot> {
        val out=ArrayList<ReplayTimingSnapshot>()
        val mergedLines=JSONObject()
        val driverByNumber=driverInfo.associateBy { it.number }
        var totalLines=0
        var timestampedLines=0
        var jsonRecords=0
        var lineDeltas=0
        var rowsProduced=0
        var firstLinePrefix=""
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            if(line.isBlank())return@forEachLine
            totalLines++
            if(firstLinePrefix.isBlank()) firstLinePrefix=line.trimStart('\uFEFF',' ','\t').take(12)
            val record=parseReplayArchiveRecord(line) ?: return@forEachLine
            timestampedLines++
            val offset=record.offsetMs
            val json=runCatching{org.json.JSONTokener(record.payload).nextValue() as? JSONObject}.getOrNull() ?: return@forEachLine
            jsonRecords++
            val delta=json.optJSONObject("Lines") ?: json.optJSONObject("lines") ?: return@forEachLine
            lineDeltas++
            // TimingData.jsonStream records after the first snapshot are sparse deltas. Merge
            // recursively before deriving rows so the standings remain stable during replay seeks.
            mergeObject(mergedLines,delta)
            val rows=ArrayList<TimingRow>()
            // TimingData is sparse, and Position is not guaranteed to be present in every
            // archived delta. Build the row set from both feeds, using the official DriverList
            // Line only as the same explicit fallback used by F1OpenViewer/FastF1.
            val driverNumbers=linkedSetOf<String>()
            driverNumbers.addAll(driverByNumber.keys)
            val timingKeys=mergedLines.keys()
            while(timingKeys.hasNext()) driverNumbers.add(timingKeys.next())
            for(number in driverNumbers){
                val d=mergedLines.optJSONObject(number) ?: JSONObject()
                val metadata=driverByNumber[number]
                val pos = sequenceOf(
                    d.optInt("Position", 0),
                    d.optInt("PositionNumber", 0),
                    d.optInt("Line", 0),
                    metadata?.line ?: 0
                ).firstOrNull { it > 0 } ?: continue
                val driver=d.optString("Tla").ifBlank{d.optString("ShortName")}
                    .ifBlank{d.optString("FullName").ifBlank{d.optString("Driver")}}
                    .ifBlank{metadata?.acronym.orEmpty()}
                    .ifBlank{metadata?.name.orEmpty()}
                if(driver.isBlank())continue
                val leaderGap=d.optString("GapToLeader").ifBlank{"-"}
                val interval=d.optJSONObject("IntervalToPositionAhead")?.optString("Value").orEmpty()
                    .ifBlank{d.optString("IntervalToPositionAhead")}.ifBlank{"-"}
                val gap=if(pos==1) "LEADER" else interval.takeIf{it.isNotBlank()&&it!="-"} ?: leaderGap
                val lastObj=d.optJSONObject("LastLapTime")
                val lastRaw=lastObj?.optString("Value").orEmpty()
                    .ifBlank{d.optString("LastLapTime").takeIf{!it.startsWith("{")} ?: d.optString("LastLap")}
                val last=lastRaw.ifBlank{"-"}
                val bestObj=d.optJSONObject("BestLapTime")
                val best=bestObj?.optString("Value").orEmpty()
                    .ifBlank{d.optString("BestLapTime").takeIf{!it.startsWith("{")}.orEmpty()}.ifBlank{"-"}
                val bestStatus=when{bestObj?.optBoolean("OverallFastest",false)==true->"PURPLE";bestObj?.optBoolean("PersonalFastest",false)==true->"GREEN";else->"NORMAL"}
                val tyre=d.optString("Compound").ifBlank{"-"}
                val s1=sectorValue(lastObj,d,"Sector1")
                val s2=sectorValue(lastObj,d,"Sector2")
                val s3=sectorValue(lastObj,d,"Sector3")
                val seg1=sectorSegments(lastObj,d,"Sector1")
                val seg2=sectorSegments(lastObj,d,"Sector2")
                val seg3=sectorSegments(lastObj,d,"Sector3")
                val speed=d.optString("Speed").ifBlank{d.optString("SpeedKmh")}.ifBlank{"-"}
                val lapNumber=d.optInt("Lap",d.optInt("LapNumber",0))
                rows += TimingRow(
                    position=pos,driver=driver,gap=gap,lastLap=last,tyre=tyre,
                    pitStops=d.optInt("NumberOfPitStops",d.optInt("PitStops",0)),
                    sector1=s1.first,sector2=s2.first,sector3=s3.first,speed=speed,bestLap=best,
                    lap=lapNumber,sector1Status=s1.second,sector2Status=s2.second,sector3Status=s3.second,
                    sector1Segments=seg1,sector2Segments=seg2,sector3Segments=seg3,
                    interval=interval,leaderGap=leaderGap,driverNumber=number,bestLapStatus=bestStatus,inPit=d.optBoolean("InPit",false),stopped=d.optBoolean("Stopped",false)
                )
            }
            if(rows.isNotEmpty()) {
                rowsProduced += rows.size
                out += ReplayTimingSnapshot(offset,rows.sortedBy{it.position})
            }
        }
        AppLogger.i(
            "ReplayTiming",
            "REPLAY_TIMING_PARSE_DIAGNOSTICS lines=$totalLines timestamped=$timestampedLines jsonRecords=$jsonRecords lineDeltas=$lineDeltas snapshots=" + out.size + " rowsProduced=$rowsProduced firstPrefix=$firstLinePrefix"
        )
        return out
    }

    private fun sectorObject(lastLap:JSONObject?,line:JSONObject,key:String):JSONObject? {
        lastLap?.optJSONObject(key)?.let{return it}
        line.optJSONObject(key)?.let{return it}
        val index=key.removePrefix("Sector").toIntOrNull()?.minus(1) ?: return null
        val array=line.optJSONArray("Sectors")
        if(array!=null)return array.optJSONObject(index)
        return line.optJSONObject("Sectors")?.optJSONObject(index.toString())
    }

    private fun sectorSegments(lastLap:JSONObject?,line:JSONObject,key:String):List<String> {
        val obj=sectorObject(lastLap,line,key) ?: return emptyList()
        val arr=obj.optJSONArray("Segments") ?: obj.optJSONArray("segments")
        if(arr!=null)return buildList{
            for(i in 0 until arr.length()) add(segmentStatus(arr.optJSONObject(i)?.optInt("Status",-1) ?: -1))
        }
        val keyed=obj.optJSONObject("Segments") ?: obj.optJSONObject("segments") ?: return emptyList()
        return keyed.keys().asSequence().mapNotNull{keyed.optJSONObject(it)?.optInt("Status",-1)}.map(::segmentStatus).toList()
    }

    private fun sectorStatus(obj:JSONObject?,value:String):String=when{
        obj?.optBoolean("OverallFastest",false)==true -> "PURPLE"
        obj?.optBoolean("PersonalFastest",false)==true -> "GREEN"
        value=="-" || value.isBlank() -> "NORMAL"
        else -> "NORMAL"
    }

    private fun sectorValue(lastLap:JSONObject?,line:JSONObject,key:String):Pair<String,String> {
        val obj=sectorObject(lastLap,line,key)
        val value=if(obj!=null) obj.optString("Value").ifBlank{obj.optString("value")}.ifBlank{"-"}
            else (lastLap?.opt(key) ?: line.opt(key))?.toString()?.takeIf{it.isNotBlank()} ?: "-"
        return value to sectorStatus(obj,value)
    }

    private fun segmentStatus(status:Int):String=when(status){
        2048->"YELLOW"
        2049->"GREEN"
        2051->"PURPLE"
        2064->"BLUE"
        else->"GRAY"
    }

    internal fun parseTimingAppData(text:String):List<ReplayStintSnapshot> {
        val out=ArrayList<ReplayStintSnapshot>()
        val merged=JSONObject()
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            val record=parseReplayArchiveRecord(line) ?: return@forEachLine
            val offset=record.offsetMs
            val root=runCatching{org.json.JSONTokener(record.payload).nextValue() as? JSONObject}.getOrNull() ?: return@forEachLine
            val delta=root.optJSONObject("Lines") ?: root.optJSONObject("lines") ?: return@forEachLine
            mergeObject(merged,delta)
            val stints=linkedMapOf<String,ReplayStint>()
            val keys=merged.keys()
            while(keys.hasNext()){
                val driver=keys.next()
                val item=merged.optJSONObject(driver) ?: continue
                val stintObject=item.optJSONObject("Stints")
                val stintArray=item.optJSONArray("Stints")
                val latest=if(stintObject!=null){
                    val latestKey=stintObject.keys().asSequence().maxByOrNull{it.toIntOrNull()?:-1}
                    latestKey?.let{stintObject.optJSONObject(it)}
                }else if(stintArray!=null && stintArray.length()>0)stintArray.optJSONObject(stintArray.length()-1)
                else item.optJSONObject("Stint")
                val compound=latest?.optString("Compound").orEmpty().ifBlank{latest?.optString("compound").orEmpty()}
                val stintNumber = when {
                    stintObject != null -> stintObject.length()
                    stintArray != null -> stintArray.length()
                    latest != null -> latest.optInt("StintNumber", item.optInt("StintNumber", 0))
                    else -> item.optInt("StintNumber", 0)
                }
                val stintLaps = when {
                    latest?.has("TotalLaps") == true -> latest.optInt("TotalLaps", -1)
                    latest?.has("StintLaps") == true -> latest.optInt("StintLaps", -1)
                    latest?.has("Laps") == true -> latest.optInt("Laps", -1)
                    item.has("StintLaps") -> item.optInt("StintLaps", -1)
                    else -> -1
                }.takeIf { it >= 0 }
                stints[driver]=ReplayStint(
                    compound=compound.uppercase(),
                    pitStops=item.optInt("NumberOfPitStops",item.optInt("PitStops",0)),
                    inPit=item.optBoolean("InPit",false)||item.optBoolean("PitIn",false),
                    stopped=item.optBoolean("Stopped",false),
                    stintNumber=stintNumber,
                    stintLaps=stintLaps
                )
            }
            if(stints.isNotEmpty())out+=ReplayStintSnapshot(offset,stints)
        }
        return out
    }

    internal fun parseCarTelemetry(text:String):List<ReplayTelemetrySnapshot> {
        val out=ArrayList<ReplayTelemetrySnapshot>()
        val latest=linkedMapOf<String,DriverTelemetry>()
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            val record=parseReplayArchiveRecord(line) ?: return@forEachLine
            val offset=record.offsetMs
            val raw=record.payload.trim()
            val root=runCatching {
                when(val token=org.json.JSONTokener(raw).nextValue()){
                    is JSONObject->token
                    is String->inflatePosition(token)
                    else->null
                }
            }.getOrNull() ?: return@forEachLine
            var changed=false
            fun numberChannel(channels:JSONObject,id:String,vararg keys:String):Int {
                val v=channels.opt(id)
                if(v is Number)return v.toInt()
                if(v is String)v.toIntOrNull()?.let{return it}
                return keys.firstNotNullOfOrNull{key->
                    channels.opt(key)?.let{value->when(value){is Number->value.toInt();is String->value.toIntOrNull();else->null}}
                } ?: 0
            }
            fun readCars(cars:JSONObject) {
                val keys=cars.keys()
                while(keys.hasNext()){
                    val driver=keys.next()
                    val car=cars.optJSONObject(driver) ?: continue
                    val channels=car.optJSONObject("Channels") ?: car
                    val existing=latest[driver]
                    latest[driver]=DriverTelemetry(
                        driver=driver,
                        speed=numberChannel(channels,"2","Speed","SpeedKmh"),
                        rpm=numberChannel(channels,"0","RPM","Rpm"),
                        gear=numberChannel(channels,"3","Gear"),
                        throttle=numberChannel(channels,"4","Throttle"),
                        brake=numberChannel(channels,"5","Brake"),
                        lap=existing?.lap ?: 0,
                        lapTime=existing?.lapTime ?: "-",
                        drs=if (channels.has("45") || channels.has("DRS")) numberChannel(channels,"45","DRS") else null
                    )
                    changed=true
                }
            }
            fun visit(value:Any?,depth:Int=0) {
                if(depth>7||value==null)return
                when(value){
                    is JSONObject->{
                        val cars=value.optJSONObject("Cars")
                        if(cars!=null)readCars(cars)
                        else {
                            val keys=value.keys()
                            while(keys.hasNext()){
                                val child=value.opt(keys.next())
                                if(child is JSONObject||child is JSONArray)visit(child,depth+1)
                            }
                        }
                    }
                    is JSONArray->for(i in 0 until value.length())visit(value.opt(i),depth+1)
                }
            }
            visit(root)
            if(changed)out+=ReplayTelemetrySnapshot(offset,latest.values.toList())
        }
        return out
    }

    private fun <T> findSnapshot(items:List<T>,target:Long,offset:(T)->Long):T? {
        var lo=0;var hi=items.lastIndex;var best=-1
        while(lo<=hi){
            val mid=(lo+hi) ushr 1
            if(offset(items[mid])<=target){best=mid;lo=mid+1}else hi=mid-1
        }
        return if(best>=0)items[best] else null
    }

    private fun mergeObject(target:JSONObject,delta:JSONObject) {
        val keys=delta.keys()
        while(keys.hasNext()) {
            val key=keys.next()
            val incoming=delta.opt(key)
            val existing=target.opt(key)
            if(incoming is JSONObject) {
                val base=if(existing is JSONObject) existing else JSONObject().also{target.put(key,it)}
                mergeObject(base,incoming)
            } else {
                target.put(key,incoming)
            }
        }
    }

    internal fun parsePositions(text:String):List<ReplayPositionSnapshot> {
        val out=ArrayList<ReplayPositionSnapshot>()
        val trails=mutableMapOf<String,MutableList<TrackPoint>>()
        val currentPositions=linkedMapOf<String,TrackDriverPosition>()
        var recordIndex=0
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            val record=parseReplayArchiveRecord(line) ?: return@forEachLine
            val offset=record.offsetMs
            // Position telemetry is high frequency. Sample every fifth archive record, as the
            // desktop reference does, to bound memory while preserving visibly smooth movement.
            if(recordIndex++ % 5 != 0)return@forEachLine
            val raw=record.payload.trim()
            val root=runCatching {
                val tok=org.json.JSONTokener(raw).nextValue()
                when(tok) {
                    is JSONObject -> tok
                    is String -> inflatePosition(tok)
                    else -> null
                }
            }.getOrNull() ?: return@forEachLine
            val snapshots=root.optJSONArray("Position") ?: JSONArray().put(root)
            val latest=linkedMapOf<String,TrackDriverPosition>()
            for(i in 0 until snapshots.length()) {
                val snap=snapshots.optJSONObject(i) ?: continue
                val entries=snap.optJSONObject("Entries") ?: continue
                val keys=entries.keys()
                while(keys.hasNext()) {
                    val number=keys.next()
                    val p=entries.optJSONObject(number) ?: continue
                    val x=p.optDouble("X",Double.NaN)
                    val y=p.optDouble("Y",Double.NaN)
                    if(!x.isFinite() || !y.isFinite())continue
                    val trail=trails.getOrPut(number){mutableListOf()}
                    trail.add(TrackPoint(x,y))
                    while(trail.size>12)trail.removeAt(0)
                    val position = TrackDriverPosition(
                        number=number, acronym=number, x=x, y=y,
                        z=p.optDouble("Z",0.0), updatedAtMs=offset,
                        trail=trail.toList()
                    )
                    currentPositions[number] = position
                    latest[number] = position
                }
            }
            if(latest.isNotEmpty())out.add(ReplayPositionSnapshot(offset,currentPositions.values.toList()))
        }
        return out
    }

    private fun inflatePosition(encoded:String):JSONObject? {
        val bytes=runCatching{Base64.getDecoder().decode(encoded)}.getOrNull() ?: return null
        val inflater=Inflater(true)
        return try {
            inflater.setInput(bytes)
            val output=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(8192)
            while(!inflater.finished()) {
                val count=inflater.inflate(buffer)
                if(count<=0)break
                output.write(buffer,0,count)
            }
            JSONObject(output.toString(Charsets.UTF_8.name()))
        } catch(_:Throwable) { null } finally { inflater.end() }
    }

    private fun parseOffset(ts:String):Long? = parseReplayArchiveOffset(ts)
    private inline fun <T> loadOptionalArchive(resource: String, fallback: T, loader: () -> T): T {
        return try {
            loader()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLogger.w(
                "ReplayTiming",
                "REPLAY_OPTIONAL_ARCHIVE_UNAVAILABLE resource=$resource reason=${failure.javaClass.simpleName} message=${failure.message}"
            )
            fallback
        }
    }

    private fun getText(url:String):String {
        val startedAtMs = System.currentTimeMillis()
        val resource = url.substringAfterLast('/')
        AppLogger.i("ReplayTiming", "REPLAY_HTTP_REQUEST_START resource=$resource")
        try {
            val response = http.newCall(
                Request.Builder().url(url).header("User-Agent", "BestHTTP").build()
            ).execute()
            response.use {
                if (!it.isSuccessful) {
                    AppLogger.w(
                        "ReplayTiming",
                        "REPLAY_HTTP_RESPONSE resource=$resource code=${it.code} durationMs=${System.currentTimeMillis() - startedAtMs}"
                    )
                    error("Timing archive HTTP " + it.code + " for " + resource)
                }
                val body = it.body?.string().orEmpty()
                AppLogger.i(
                    "ReplayTiming",
                    "REPLAY_HTTP_RESPONSE resource=$resource code=${it.code} bytes=${body.toByteArray(Charsets.UTF_8).size} durationMs=${System.currentTimeMillis() - startedAtMs}"
                )
                return body
            }
        } catch (failure: Exception) {
            AppLogger.w(
                "ReplayTiming",
                "REPLAY_HTTP_FAILURE resource=$resource durationMs=${System.currentTimeMillis() - startedAtMs} reason=${failure.javaClass.simpleName} message=${failure.message}"
            )
            throw failure
        }
    }
    private fun getJson(url:String)=JSONObject(getText(url))
    private fun loadCuratedSync(meetingKey:String,sessionKey:String):ReplaySyncData?=runCatching{
        if(meetingKey.isBlank()||sessionKey.isBlank())return null
        val j=getJson("https://api.multiviewer.app/api/v1/meetings/$meetingKey/sessions/$sessionKey")
        val start=(j.optDouble("session_start",Double.NaN)*1000.0).takeIf{it.isFinite()}?.toLong() ?: return null
        val offsets=j.optJSONObject("sync_offsets")?.optJSONArray("sync_offsets")
        val diffs=mutableMapOf<String,Long>()
        if(offsets!=null) for(i in 0 until offsets.length()){
            val o=offsets.optJSONObject(i) ?: continue
            val cid=o.optJSONObject("streamData")?.optString("channelId").orEmpty()
            if(cid.isNotBlank()) diffs[cid]=(o.optDouble("diffV2",o.optDouble("diff",0.0))*1000.0).toLong()
        }
        ReplaySyncData(start,diffs)
    }.getOrNull()
}