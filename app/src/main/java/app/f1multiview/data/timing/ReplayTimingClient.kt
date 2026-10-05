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
class ReplayTimingClient {
    private val http=OkHttpClient()
    private var snapshots:List<ReplayTimingSnapshot> = emptyList()
    private var syncOffsetMs:Long = 0L
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
            syncOffsetMs=loadCuratedOffset(meeting.optString("Key"),session.optString("Key")) ?: 0L
        }
    }
    fun rowsAt(videoPositionMs:Long):List<TimingRow> {
        val target=(videoPositionMs+syncOffsetMs).coerceAtLeast(0L)
        return snapshots.lastOrNull{it.offsetMs<=target}?.rows.orEmpty()
    }
    fun isLoaded()=snapshots.isNotEmpty()
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
    private fun loadCuratedOffset(meetingKey:String,sessionKey:String):Long?=runCatching{
        if(meetingKey.isBlank()||sessionKey.isBlank())return null
        val j=getJson("https://api.multiviewer.app/api/v1/meetings/$meetingKey/sessions/$sessionKey")
        (j.optDouble("session_start",Double.NaN)*1000.0).takeIf{it.isFinite()}?.toLong()
    }.getOrNull()
}