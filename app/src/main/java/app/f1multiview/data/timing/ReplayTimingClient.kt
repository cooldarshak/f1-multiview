package app.f1multiview.data.timing
import app.f1multiview.model.TimingRow
import app.f1multiview.model.DriverTelemetry
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
import java.util.Base64
import org.json.JSONArray

data class ReplayTimingSnapshot(val offsetMs:Long,val rows:List<TimingRow>)
data class ReplayPositionSnapshot(val offsetMs:Long,val positions:List<TrackDriverPosition>)
data class ReplayTelemetrySnapshot(val offsetMs:Long,val telemetry:List<DriverTelemetry>)
private data class ReplayStint(val compound:String,val pitStops:Int,val inPit:Boolean)
private data class ReplayStintSnapshot(val offsetMs:Long,val stints:Map<String,ReplayStint>)
data class ReplaySyncData(val sessionStartMs:Long,val channelDiffs:Map<String,Long>)
class ReplayTimingClient {
    private val http=OkHttpClient()
    private var snapshots:List<ReplayTimingSnapshot> = emptyList()
    private var positionSnapshots:List<ReplayPositionSnapshot> = emptyList()
    private var telemetrySnapshots:List<ReplayTelemetrySnapshot> = emptyList()
    private var stintSnapshots:List<ReplayStintSnapshot> = emptyList()
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
            stintSnapshots=runCatching { parseTimingAppData(getText("https://livetiming.formula1.com/static/$path/TimingAppData.jsonStream")) }.getOrDefault(emptyList())
            // These two compressed feeds are needed to keep car telemetry and map markers tied to
            // the replay media clock, including backwards seeks. No live values are mixed in.
            telemetrySnapshots=runCatching { parseCarTelemetry(getText("https://livetiming.formula1.com/static/$path/CarData.z.jsonStream")) }.getOrDefault(emptyList())
            positionSnapshots=runCatching { parsePositions(getText("https://livetiming.formula1.com/static/$path/Position.z.jsonStream")) }.getOrDefault(emptyList())
            syncData=loadCuratedSync(meeting.optString("Key"),session.optString("Key")) ?: ReplaySyncData(0L, emptyMap())
            syncOffsetMs=syncData.sessionStartMs
        }
    }
    fun rowsAt(videoPositionMs:Long):List<TimingRow> {
        val target=(videoPositionMs+syncOffsetMs).coerceAtLeast(0L)
        val rows=findSnapshot(snapshots,target){it.offsetMs}?.rows ?: return emptyList()
        val stints=findSnapshot(stintSnapshots,target){it.offsetMs}?.stints.orEmpty()
        return rows.map { row ->
            val stint=stints[row.driverNumber]
            row.copy(
                tyre=stint?.compound?.takeIf{it.isNotBlank()} ?: row.tyre,
                pitStops=stint?.pitStops ?: row.pitStops,
                lastLap=if(stint?.inPit==true) "IN PIT" else row.lastLap
            )
        }
    }

    fun telemetryAt(videoPositionMs:Long):List<DriverTelemetry> {
        val target=(videoPositionMs+syncOffsetMs).coerceAtLeast(0L)
        val snapshot=findSnapshot(telemetrySnapshots,target){it.offsetMs} ?: return emptyList()
        val rows=rowsAt(videoPositionMs).associateBy{it.driverNumber}
        return snapshot.telemetry.map { item ->
            val row=rows[item.driver]
            item.copy(lap=row?.lap ?: item.lap,lapTime=row?.lastLap ?: item.lapTime)
        }
    }

    fun positionsAt(videoPositionMs:Long):List<TrackDriverPosition> {
        val target=(videoPositionMs+syncOffsetMs).coerceAtLeast(0L)
        return findSnapshot(positionSnapshots,target){it.offsetMs}?.positions.orEmpty()
    }

    fun positionSnapshotCount():Int = positionSnapshots.size
    fun telemetrySnapshotCount():Int = telemetrySnapshots.size

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
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            if(line.length<12)return@forEachLine
            val offset=parseOffset(line.substring(0,12)) ?: return@forEachLine
            val json=runCatching{JSONObject(line.substring(12))}.getOrNull() ?: return@forEachLine
            val delta=json.optJSONObject("Lines") ?: json.optJSONObject("lines") ?: return@forEachLine
            // TimingData.jsonStream records after the first snapshot are sparse deltas. Merge
            // recursively before deriving rows so the standings remain stable during replay seeks.
            mergeObject(mergedLines,delta)
            val rows=ArrayList<TimingRow>()
            val keys=mergedLines.keys()
            while(keys.hasNext()){
                val number=keys.next()
                val d=mergedLines.optJSONObject(number) ?: continue
                val pos=d.optInt("Position",d.optInt("PositionNumber",0))
                val driver=d.optString("Tla").ifBlank{d.optString("ShortName")}
                    .ifBlank{d.optString("FullName").ifBlank{d.optString("Driver")}}
                if(pos<=0 || driver.isBlank())continue
                val leaderGap=d.optString("GapToLeader").ifBlank{"-"}
                val interval=d.optJSONObject("IntervalToPositionAhead")?.optString("Value").orEmpty()
                    .ifBlank{d.optString("IntervalToPositionAhead")}.ifBlank{"-"}
                val gap=if(pos==1) "LEADER" else interval.takeIf{it.isNotBlank()&&it!="-"} ?: leaderGap
                val lastObj=d.optJSONObject("LastLapTime")
                val lastRaw=lastObj?.optString("Value").orEmpty()
                    .ifBlank{d.optString("LastLapTime").takeIf{!it.startsWith("{")} ?: d.optString("LastLap")}
                val last=if(d.optBoolean("InPit",false)) "IN PIT" else lastRaw.ifBlank{"-"}
                val bestObj=d.optJSONObject("BestLapTime")
                val best=bestObj?.optString("Value").orEmpty()
                    .ifBlank{d.optString("BestLapTime").takeIf{!it.startsWith("{")}.orEmpty()}.ifBlank{"-"}
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
                    interval=interval,leaderGap=leaderGap,driverNumber=number
                )
            }
            if(rows.isNotEmpty())out += ReplayTimingSnapshot(offset,rows.sortedBy{it.position})
        }
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
        else -> "YELLOW"
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

    private fun parseTimingAppData(text:String):List<ReplayStintSnapshot> {
        val out=ArrayList<ReplayStintSnapshot>()
        val merged=JSONObject()
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            if(line.length<12)return@forEachLine
            val offset=parseOffset(line.substring(0,12)) ?: return@forEachLine
            val root=runCatching{JSONObject(line.substring(12))}.getOrNull() ?: return@forEachLine
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
                stints[driver]=ReplayStint(
                    compound=compound.uppercase(),
                    pitStops=item.optInt("NumberOfPitStops",item.optInt("PitStops",0)),
                    inPit=item.optBoolean("InPit",false)||item.optBoolean("PitIn",false)
                )
            }
            if(stints.isNotEmpty())out+=ReplayStintSnapshot(offset,stints)
        }
        return out
    }

    private fun parseCarTelemetry(text:String):List<ReplayTelemetrySnapshot> {
        val out=ArrayList<ReplayTelemetrySnapshot>()
        val latest=linkedMapOf<String,DriverTelemetry>()
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
            if(line.length<13)return@forEachLine
            val offset=parseOffset(line.substring(0,12)) ?: return@forEachLine
            val raw=line.substring(12).trim()
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
                        lapTime=existing?.lapTime ?: "-"
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
        var recordIndex=0
        BufferedReader(StringReader(text.removePrefix("\uFEFF"))).forEachLine { line ->
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