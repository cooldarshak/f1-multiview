package app.f1multiview.data.timing
import app.f1multiview.model.TimingRow
import app.f1multiview.model.TrackDriverPosition
import app.f1multiview.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader
import java.util.zip.Inflater
import android.util.Base64
import org.json.JSONArray

data class ReplayTimingSnapshot(val offsetMs:Long,val rows:List<TimingRow>)
data class ReplayPositionSnapshot(val offsetMs:Long,val positions:List<TrackDriverPosition>)
data class ReplaySyncData(val sessionStartMs:Long,val channelDiffs:Map<String,Long>)
class ReplayTimingClient {
    private val http=OkHttpClient()
    private var snapshots:List<ReplayTimingSnapshot> = emptyList()
    private var positionSnapshots:List<ReplayPositionSnapshot> = emptyList()
    private var syncOffsetMs:Long = 0L
    private var syncData = ReplaySyncData(0L, emptyMap())
    suspend fun load(year:Int, meetingNumber:Int, sessionType:String):Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val index=getJson("https://livetiming.formula1.com/static/$year/Index.json")
            val meetings=index.optJSONArray("Meetings") ?: error("Timing archive has no meetings")
            val meeting=(0 until meetings.length()).map{meetings.getJSONObject(it)}.firstOrNull{it.optInt("Number",-1)==meetingNumber} ?: error("Timing archive meeting not found")
            val sessions=meeting.optJSONArray("Sessions") ?: error("Timing archive has no sessions")
            val wanted=when {
                sessionType.contains("sprint",true) && sessionType.contains("qual",true) -> "Sprint Qualifying"
                sessionType.contains("sprint",true) -> "Sprint"
                sessionType.contains("qual",true) -> "Qualifying"
                sessionType.contains("practice 1",true) || sessionType=="practice-1" -> "Practice 1"
                sessionType.contains("practice 2",true) || sessionType=="practice-2" -> "Practice 2"
                sessionType.contains("practice 3",true) || sessionType=="practice-3" -> "Practice 3"
                sessionType.contains("race",true) -> "Race"
                else -> sessionType
            }
            val session=(0 until sessions.length()).map{sessions.getJSONObject(it)}.firstOrNull{it.optString("Name").contains(wanted,true) || it.optString("Type").equals(wanted,true)} ?: error("Timing archive session not found")
            val path=session.optString("Path").trim('/').ifBlank{error("Timing archive session has no path")}
            snapshots=parse(getText("https://livetiming.formula1.com/static/$path/TimingData.jsonStream"))
            // Position.z is the archived source for the moving track-map markers. Keep it on the
            // same session-relative clock as TimingData so replay seeking can reconstruct both.
            positionSnapshots=runCatching { parsePositions(getText("https://livetiming.formula1.com/static/$path/Position.z.jsonStream")) }.getOrDefault(emptyList())
            syncData=loadCuratedSync(meeting.optString("Key"),session.optString("Key")) ?: ReplaySyncData(0L, emptyMap())
            syncOffsetMs=syncData.sessionStartMs
        }
    }
    fun rowsAt(videoPositionMs:Long):List<TimingRow> {
        val target=(videoPositionMs+syncOffsetMs).coerceAtLeast(0L)
        if(snapshots.isEmpty()) return emptyList()
        var lo=0
        var hi=snapshots.lastIndex
        var best=-1
        while(lo<=hi){
            val mid=(lo+hi) ushr 1
            if(snapshots[mid].offsetMs<=target){best=mid;lo=mid+1}else hi=mid-1
        }
        return if(best>=0) snapshots[best].rows else emptyList()
    }

    /**
     * Latest archived car positions at the requested video position. This is replay state, not
     * live state: callers should replace the map's driver positions with this snapshot on every
     * playback time update, including backwards seeks.
     */
    fun positionsAt(videoPositionMs:Long):List<TrackDriverPosition> {
        val target=(videoPositionMs+syncOffsetMs).coerceAtLeast(0L)
        if(positionSnapshots.isEmpty()) return emptyList()
        var lo=0
        var hi=positionSnapshots.lastIndex
        var best=-1
        while(lo<=hi){
            val mid=(lo+hi) ushr 1
            if(positionSnapshots[mid].offsetMs<=target){best=mid;lo=mid+1}else hi=mid-1
        }
        return if(best>=0) positionSnapshots[best].positions else emptyList()
    }

    fun positionSnapshotCount():Int = positionSnapshots.size

    fun setSyncOffset(offsetMs:Long){ syncOffsetMs=offsetMs }

    fun nudge(deltaMs:Long){ syncOffsetMs += deltaMs }

    fun calibratedOffset(videoPositionMs:Long, timingPositionMs:Long):Long =
        (timingPositionMs-videoPositionMs).coerceIn(-300_000L,300_000L)

    fun snapshotCount():Int = snapshots.size
    fun isLoaded()=snapshots.isNotEmpty()
    fun sync():ReplaySyncData=syncData
    internal fun parse(text:String):List<ReplayTimingSnapshot> {
        val out=ArrayList<ReplayTimingSnapshot>()
        val mergedLines=JSONObject()
        BufferedReader(StringReader(text.removePrefix("\\uFEFF"))).forEachLine { line ->
            if(line.length<12)return@forEachLine
            val offset=parseOffset(line.substring(0,12)) ?: return@forEachLine
            val json=runCatching{JSONObject(line.substring(12))}.getOrNull() ?: return@forEachLine
            val delta=json.optJSONObject("Lines") ?: json.optJSONObject("lines") ?: return@forEachLine
            // TimingData.jsonStream records after the first snapshot are sparse deltas. Merge
            // recursively before deriving rows; otherwise drivers disappear or retain stale gaps
            // during replay seeks.
            mergeObject(mergedLines,delta)
            val rows=ArrayList<TimingRow>()
            val keys=mergedLines.keys()
            while(keys.hasNext()){
                val d=mergedLines.optJSONObject(keys.next()) ?: continue
                val pos=d.optInt("Position",d.optInt("PositionNumber",0))
                val driver=d.optString("FullName").ifBlank{d.optString("Tla").ifBlank{d.optString("Driver")}}
                if(pos<=0 || driver.isBlank())continue
                val gap=d.optString("GapToLeader").ifBlank{d.optString("TimeDiffToFastest")}.ifBlank{"-"}
                val lastObj=d.optJSONObject("LastLapTime")
                val lap=lastObj?.optString("Value").orEmpty().ifBlank{d.optString("LastLapTime").takeIf{!it.startsWith("{")} ?: d.optString("LastLap")}.ifBlank{"-"}
                val bestObj=d.optJSONObject("BestLapTime")
                val tyre=bestObj?.optString("Compound").orEmpty().ifBlank{d.optString("Compound")}.ifBlank{"-"}
                rows += TimingRow(pos,driver,gap,lap,tyre,d.optInt("NumberOfPitStops",0))
            }
            if(rows.isNotEmpty())out += ReplayTimingSnapshot(offset,rows.sortedBy{it.position})
        }
        return out
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
        var recordIndex=0
        BufferedReader(StringReader(text.removePrefix("\\uFEFF"))).forEachLine { line ->
            if(line.length<13)return@forEachLine
            val offset=parseOffset(line.substring(0,12)) ?: return@forEachLine
            // Position telemetry is high frequency. Sample every fifth archive record, as the
            // desktop reference does, to bound memory while preserving visibly smooth movement.
            if(recordIndex++ % 5 != 0)return@forEachLine
            val raw=line.substring(12).trim()
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
                    latest[number]=TrackDriverPosition(
                        number=number, acronym=number, x=x, y=y,
                        z=p.optDouble("Z",0.0), updatedAtMs=offset,
                        trail=trail.toList()
                    )
                }
            }
            if(latest.isNotEmpty())out.add(ReplayPositionSnapshot(offset,latest.values.toList()))
        }
        return out
    }

    private fun inflatePosition(encoded:String):JSONObject? {
        val bytes=runCatching{Base64.decode(encoded,Base64.DEFAULT)}.getOrNull() ?: return null
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

    private fun parseOffset(ts:String):Long?=runCatching{val p=ts.split(":",".");if(p.size!=4)null else ((p[0].toLong()*3600+p[1].toLong()*60+p[2].toLong())*1000+p[3].toLong())}.getOrNull()
    private fun getText(url:String):String { val r=http.newCall(Request.Builder().url(url).header("User-Agent","BestHTTP").build()).execute(); r.use{if(!it.isSuccessful)error("Timing archive HTTP "+it.code);return it.body?.string().orEmpty()} }
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