package app.f1multiview.data.timing
import app.f1multiview.model.TimingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader

data class ReplayTimingSnapshot(val offsetMs:Long,val rows:List<TimingRow>)
data class ReplaySyncData(val sessionStartMs:Long,val channelDiffs:Map<String,Long>)
class ReplayTimingClient {
    private val http=OkHttpClient()
    private var snapshots:List<ReplayTimingSnapshot> = emptyList()
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

    fun setSyncOffset(offsetMs:Long){ syncOffsetMs=offsetMs }

    fun nudge(deltaMs:Long){ syncOffsetMs += deltaMs }

    fun calibratedOffset(videoPositionMs:Long, timingPositionMs:Long):Long =
        (timingPositionMs-videoPositionMs).coerceIn(-300_000L,300_000L)

    fun snapshotCount():Int = snapshots.size
    fun isLoaded()=snapshots.isNotEmpty()
    fun sync():ReplaySyncData=syncData
    private fun parse(text:String):List<ReplayTimingSnapshot> {
        val out=ArrayList<ReplayTimingSnapshot>()
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            if(line.length<12)return@forEachLine
            val offset=parseOffset(line.substring(0,12)) ?: return@forEachLine
            val json=runCatching{JSONObject(line.substring(12))}.getOrNull() ?: return@forEachLine
            val lines=json.optJSONObject("Lines") ?: json.optJSONObject("lines") ?: return@forEachLine
            val rows=ArrayList<TimingRow>(); val keys=lines.keys()
            while(keys.hasNext()){
                val d=lines.optJSONObject(keys.next()) ?: continue
                val pos=d.optInt("Position",d.optInt("PositionNumber",0))
                val driver=d.optString("FullName").ifBlank{d.optString("Tla").ifBlank{d.optString("Driver")}}
                if(pos<=0 || driver.isBlank())continue
                val gap=d.optString("GapToLeader").ifBlank{d.optString("TimeDiffToFastest")}.ifBlank{"-"}
                val lap=d.optString("LastLapTime").ifBlank{d.optString("LastLap")}.ifBlank{"-"}
                val tyre=d.optJSONObject("BestLapTime")?.optString("Compound").orEmpty().ifBlank{d.optString("Compound").ifBlank{"-"}}
                rows += TimingRow(pos,driver,gap,lap,tyre,d.optInt("NumberOfPitStops",0))
            }
            if(rows.isNotEmpty())out += ReplayTimingSnapshot(offset,rows.sortedBy{it.position})
        }
        return out
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