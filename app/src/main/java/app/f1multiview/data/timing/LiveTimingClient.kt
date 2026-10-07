package app.f1multiview.data.timing

import app.f1multiview.model.TimingRow
import app.f1multiview.model.RaceControlEvent
import app.f1multiview.model.TimingWeather
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64
import app.f1multiview.model.*
import java.util.zip.Inflater
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LiveTimingClient(private val scope:CoroutineScope, private val authHeadersProvider:suspend () -> Map<String,String> = { emptyMap() }){
    companion object{
        private const val BASE="https://livetiming.formula1.com"
        private const val NEGOTIATE="$BASE/signalrcore/negotiate?negotiateVersion=1"
        private const val WS="wss://livetiming.formula1.com/signalrcore?id="
        private const val RS='\u001e'
        private const val KEEPALIVE_MS=15000L
        private val FEEDS=listOf("SessionInfo","DriverList","TimingData","TimingAppData","TimingStats","CarData.z","Position.z","WeatherData","TrackStatus","RaceControlMessages","LapCount","TopThree","TeamRadio")
    }
    private val http=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).build()
    private val _rows=MutableStateFlow<List<TimingRow>>(emptyList());val rows: StateFlow<List<TimingRow>> = _rows.asStateFlow()
    private val _status=MutableStateFlow("OFFLINE");val status:StateFlow<String> = _status.asStateFlow()
    private val _raceControl=MutableStateFlow<List<RaceControlEvent>>(emptyList());val raceControl:StateFlow<List<RaceControlEvent>> = _raceControl.asStateFlow()
    private val _weather=MutableStateFlow(TimingWeather());val weather:StateFlow<TimingWeather> = _weather.asStateFlow()
    private val _teamRadio=MutableStateFlow<List<app.f1multiview.model.TeamRadioItem>>(emptyList());val teamRadio:StateFlow<List<app.f1multiview.model.TeamRadioItem>> = _teamRadio.asStateFlow()
    private val _telemetry=MutableStateFlow<List<app.f1multiview.model.DriverTelemetry>>(emptyList());val telemetry:StateFlow<List<app.f1multiview.model.DriverTelemetry>> = _telemetry.asStateFlow()
    private val _sessionInfo=MutableStateFlow(app.f1multiview.model.LiveSessionInfo());val sessionInfo:StateFlow<app.f1multiview.model.LiveSessionInfo> = _sessionInfo.asStateFlow()
    private val _trackPositions=MutableStateFlow<List<TrackDriverPosition>>(emptyList())
    val trackPositions:StateFlow<List<TrackDriverPosition>> = _trackPositions.asStateFlow()
    private val _trackStatus=MutableStateFlow(TrackStatusInfo())
    private val _lapCount=MutableStateFlow(0 to 0)
    val lapCount:StateFlow<Pair<Int,Int>> = _lapCount.asStateFlow()
    val trackStatus:StateFlow<TrackStatusInfo> = _trackStatus.asStateFlow()
    private val driverMeta=mutableMapOf<String,DriverMeta>()
    private val timingMeta=mutableMapOf<String,TimingMeta>()
    private val positionMeta=mutableMapOf<String,TrackPositionRaw>()
    // F1 SignalR sends TimingData as differential updates. Keep the latest merged Lines state.
    private val timingLinesState=JSONObject()
    private val timingLock=Any()
    private var socket:WebSocket?=null;private var reconnect:Job?=null;private var keepAlive:Job?=null;@Volatile private var affinityCookie:String?=null
    fun start(){if(socket!=null||reconnect?.isActive==true)return;connect()}
    fun stop(){reconnect?.cancel();reconnect=null;keepAlive?.cancel();keepAlive=null;socket?.close(1000,"stop");socket=null;affinityCookie=null;_status.value="OFFLINE"}
    fun restart(){ stop(); start() }
    private fun connect(){scope.launch(Dispatchers.IO){try{
        _status.value="CONNECTING";val authHeaders=runCatching{authHeadersProvider()}.getOrDefault(emptyMap());affinityCookie=fetchAffinityCookie(authHeaders)?:affinityCookie
        val token=negotiate(authHeaders)?:throw IllegalStateException("Timing negotiation returned no connection token")
        val requestBuilder=Request.Builder().url(WS+java.net.URLEncoder.encode(token,"UTF-8")).apply{affinityCookie?.let{header("Cookie",it)}}.header("User-Agent","F1MultiView/1.0 Android")
        runCatching { authHeadersProvider() }.getOrDefault(emptyMap()).forEach { (key,value) -> requestBuilder.header(key,value) }
        val request=requestBuilder.build()
        socket=http.newWebSocket(request,Listener())
    }catch(_:Throwable){_status.value="RETRYING";scheduleReconnect()}}}
    private fun fetchAffinityCookie(authHeaders:Map<String,String>):String?{
        val reqBuilder=Request.Builder().url("$BASE/signalrcore/negotiate").method("OPTIONS",null).header("User-Agent","F1MultiView/1.0 Android");authHeaders.forEach{(k,v)->reqBuilder.header(k,v)};val req=reqBuilder.build()
        return runCatching{http.newCall(req).execute().use{r->r.headers.values("Set-Cookie").firstNotNullOfOrNull{c->c.substringBefore(';').takeIf{it.startsWith("AWSALBCORS=",true)}}}}.getOrNull()
    }
    private fun negotiate(authHeaders:Map<String,String>):String?{
        val reqBuilder=Request.Builder().url(NEGOTIATE).post("".toRequestBody("application/json".toMediaType())).header("User-Agent","F1MultiView/1.0 Android").apply{affinityCookie?.let{header("Cookie",it)}};authHeaders.forEach{(k,v)->reqBuilder.header(k,v)};val req=reqBuilder.build()
        http.newCall(req).execute().use{r->if(!r.isSuccessful)return null;val json=JSONObject(r.body?.string().orEmpty());return json.optString("connectionToken").ifBlank{json.optString("connectionId")}.takeIf{it.isNotBlank()}}
    }
    private fun scheduleReconnect(){if(reconnect?.isActive==true)return;reconnect=scope.launch{delay(2000);if(isActive)connect()}}
    private inner class Listener:WebSocketListener(){
        override fun onOpen(ws:WebSocket,response:Response){_status.value="HANDSHAKING";ws.send("{\"protocol\":\"json\",\"version\":1}$RS")}
        override fun onMessage(ws:WebSocket,text:String){text.split(RS).filter{it.isNotBlank()}.forEach{frame->
            val json=runCatching{JSONObject(frame)}.getOrNull()?:return@forEach
            if(json.optString("error").isNotBlank()){_status.value="ERROR";ws.close(1002,json.optString("error"));return@forEach}
            if(_status.value=="HANDSHAKING"){_status.value="LIVE";val subscribe=JSONObject().put("type",1).put("invocationId","1").put("target","Subscribe").put("arguments",org.json.JSONArray().put(FEEDS.toTypedArray()));ws.send(subscribe.toString()+RS);keepAlive?.cancel();keepAlive=scope.launch{while(isActive){delay(KEEPALIVE_MS);if(socket===ws)ws.send(JSONObject().put("type",6).toString()+RS)}}}
            if(json.optInt("type")==1&&json.optString("target")=="feed"){val args=json.optJSONArray("arguments")?:return@forEach;if(args.length()>=2){val feed=args.optString(0);val data=decodeFeedObject(args.opt(1));when(feed){"TimingData"->parseTiming(data);"DriverList"->parseDriverList(data);"SessionInfo"->parseSessionInfo(data);"CarData.z","CarData"->parseCarData(data);"Position.z","Position"->parsePosition(data);"TrackStatus"->parseTrackStatus(data);"RaceControlMessages"->parseRaceControl(data);"WeatherData"->parseWeather(data);"LapCount"->parseLapCount(data);"TeamRadio"->parseTeamRadio(data)}}}
            if(json.optInt("type")==3){val result=json.optJSONObject("result")?:return@forEach;val it=result.keys();while(it.hasNext()){val feed=it.next();val data=decodeFeedObject(result.opt(feed));when(feed){"TimingData"->parseTiming(data);"DriverList"->parseDriverList(data);"SessionInfo"->parseSessionInfo(data);"CarData.z","CarData"->parseCarData(data);"Position.z","Position"->parsePosition(data);"TrackStatus"->parseTrackStatus(data);"RaceControlMessages"->parseRaceControl(data);"WeatherData"->parseWeather(data);"LapCount"->parseLapCount(data);"TimingDataF1"->parseTiming(data);"TeamRadio"->parseTeamRadio(data)}}}
        }}
        override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){if(socket===ws)socket=null;keepAlive?.cancel();keepAlive=null;_status.value="RETRYING";scheduleReconnect()}
        override fun onClosed(ws:WebSocket,code:Int,reason:String){if(socket===ws)socket=null;keepAlive?.cancel();keepAlive=null;if(code!=1000){_status.value="RETRYING";scheduleReconnect()}else _status.value="OFFLINE"}
    }
    private fun decodeFeedObject(raw:Any?):JSONObject? {
        if(raw is JSONObject) return raw
        val encoded = raw as? String ?: return null
        if(encoded.isBlank()) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        val inflater = Inflater(true)
        return try {
            inflater.setInput(bytes)
            val out = ByteArray(1024 * 1024)
            val size = inflater.inflate(out)
            if(size > 0) JSONObject(String(out, 0, size, Charsets.UTF_8))
            else runCatching { JSONObject(encoded) }.getOrNull()
        } catch(_:Throwable) {
            runCatching { JSONObject(encoded) }.getOrNull()
        } finally {
            inflater.end()
        }
    }

    private data class DriverMeta(val name:String,val acronym:String,val team:String,val teamColor:String)
    private data class TimingMeta(val position:Int=0,val speed:Int=0,val lap:Int=0,val inPit:Boolean=false,val stopped:Boolean=false,val retired:Boolean=false)
    private data class TrackPositionRaw(val x:Double,val y:Double,val z:Double,val trail:List<TrackPoint> = emptyList())

    private fun publishTrackPositions() {
        val now=System.currentTimeMillis()
        _trackPositions.value=positionMeta.map { (number,pos) ->
            val driver=driverMeta[number]
            val timing=timingMeta[number] ?: TimingMeta()
            TrackDriverPosition(number=number,name=driver?.name ?: number,acronym=driver?.acronym ?: number,team=driver?.team ?: "-",teamColor=driver?.teamColor ?: "FFFFFF",x=pos.x,y=pos.y,z=pos.z,position=timing.position,speed=timing.speed,lap=timing.lap,inPit=timing.inPit,stopped=timing.stopped,retired=timing.retired,updatedAtMs=now,trail=pos.trail)
        }.sortedWith(compareBy<TrackDriverPosition> { it.position.takeIf { p -> p > 0 } ?: 999 }.thenBy { it.number })
    }
    private fun parseDriverList(data:JSONObject?) {
        if(data == null) return
        val root=data.optJSONObject("DriverList") ?: data
        val keys=root.keys()
        while(keys.hasNext()){
            val number=keys.next()
            val x=root.optJSONObject(number) ?: continue
            val racingNumber=x.optString("RacingNumber").ifBlank{number}
            driverMeta[racingNumber]=DriverMeta(
                name=x.optString("FullName").ifBlank{listOf(x.optString("FirstName"),x.optString("LastName")).filter(String::isNotBlank).joinToString(" ")}.ifBlank{x.optString("BroadcastName")}.ifBlank{racingNumber},
                acronym=x.optString("Tla").ifBlank{x.optString("ShortName")}.ifBlank{racingNumber},
                team=x.optString("TeamName").ifBlank{x.optString("Team")}.ifBlank{"-"},
                teamColor=x.optString("TeamColour").ifBlank{x.optString("TeamColor")}.ifBlank{"FFFFFF"}
            )
        }
        publishTrackPositions()
    }
    private fun parseSessionInfo(data:JSONObject?) {
        if(data == null) return
        val info=data.optJSONObject("SessionInfo") ?: data
        val meeting=info.optJSONObject("Meeting") ?: JSONObject()
        val circuit=meeting.optJSONObject("Circuit") ?: JSONObject()
        val year=info.optString("StartDate").takeIf{it.length>=4}?.take(4)?.toIntOrNull() ?: info.optString("EndDate").takeIf{it.length>=4}?.take(4)?.toIntOrNull()
        val countryObj=meeting.optJSONObject("Country") ?: JSONObject()
        _sessionInfo.value=LiveSessionInfo(
            name=info.optString("Name").ifBlank{info.optString("MeetingName")}.ifBlank{"-"},
            meeting=info.optString("MeetingName").ifBlank{meeting.optString("Name")}.ifBlank{"-"},
            country=countryObj.optString("Name").ifBlank{countryObj.optString("Code")}.ifBlank{info.optString("Country")}.ifBlank{info.optString("Location")}.ifBlank{"-"},
            sessionType=info.optString("Type").ifBlank{info.optString("SessionType")}.ifBlank{"-"},
            status=info.optString("Status").ifBlank{_status.value},
            circuitKey=circuit.optInt("Key",0).takeIf{it>0},
            year=year
        )
    }

    private fun parseCarData(data:JSONObject?) {
        if(data==null)return
        val entries=data.optJSONArray("Entries") ?: return
        for(i in 0 until entries.length()){
            val entry=entries.optJSONObject(i) ?: continue
            val cars=entry.optJSONObject("Cars") ?: continue
            val keys=cars.keys()
            val out=mutableListOf<app.f1multiview.model.DriverTelemetry>()
            while(keys.hasNext()){
                val number=keys.next()
                val car=cars.optJSONObject(number) ?: continue
                val channels=car.optJSONObject("Channels") ?: car
                val speed=channels.optInt("2",0)
                val rpm=channels.optInt("0",0)
                val gear=channels.optInt("3",0)
                val throttle=channels.optInt("4",0)
                val brake=channels.optInt("5",0)
                val drs=channels.optInt("45",0)>0
                val existing=_telemetry.value.firstOrNull{it.driver==number}
                out += app.f1multiview.model.DriverTelemetry(
                    driver=number,
                    speed=speed,
                    rpm=rpm,
                    gear=gear,
                    throttle=throttle,
                    brake=brake,
                    drs=drs,
                    lap=existing?.lap ?: 0,
                    lapTime=existing?.lapTime ?: "-"
                )
            }
            if(out.isNotEmpty()) _telemetry.value=out
        }
    }

    private fun parsePosition(data:JSONObject?) {
        if(data == null) return
        val snapshots=data.optJSONArray("Position")
        if(snapshots!=null){
            for(i in 0 until snapshots.length()){
                val snapshot=snapshots.optJSONObject(i) ?: continue
                val entries=snapshot.optJSONObject("Entries") ?: continue
                val keys=entries.keys()
                while(keys.hasNext()){
                    val number=keys.next();val p=entries.optJSONObject(number) ?: continue
                    val x=p.optDouble("X",Double.NaN);val y=p.optDouble("Y",Double.NaN)
                    if(x.isFinite()&&y.isFinite()){ val previous=positionMeta[number]?.trail.orEmpty(); positionMeta[number]=TrackPositionRaw(x,y,p.optDouble("Z",0.0),(previous+TrackPoint(x,y)).takeLast(8)) }
                }
            }
        } else {
            val entries=data.optJSONObject("Entries") ?: data
            val keys=entries.keys()
            while(keys.hasNext()){
                val number=keys.next();val p=entries.optJSONObject(number) ?: continue
                val x=p.optDouble("X",Double.NaN);val y=p.optDouble("Y",Double.NaN)
                if(x.isFinite()&&y.isFinite()){ val previous=positionMeta[number]?.trail.orEmpty(); positionMeta[number]=TrackPositionRaw(x,y,p.optDouble("Z",0.0),(previous+TrackPoint(x,y)).takeLast(8)) }
            }
        }
        publishTrackPositions()
    }

    private fun parseTrackStatus(data:JSONObject?) {
        if(data==null) return
        val root=data.optJSONObject("TrackStatus") ?: data
        val code=root.optString("Status").toIntOrNull() ?: root.optInt("Status",1)
        val label=when(code){1->"GREEN";2->"YELLOW";4->"SAFETY CAR";5->"RED";6->"VSC";7->"VSC ENDING";else->"STATUS $code"}
        _trackStatus.value=TrackStatusInfo(code,label,root.optString("Message"))
    }

    private fun parseTelemetryObject(data:JSONObject?) {
        if(data == null) return
        val source = data.optJSONObject("Entries") ?: data.optJSONObject("Cars") ?: data.optJSONObject("Lines") ?: data
        val out = mutableListOf<app.f1multiview.model.DriverTelemetry>()
        val keys = source.keys()
        while(keys.hasNext()) {
            val key = keys.next()
            val x = source.optJSONObject(key) ?: continue
            val speed = number(x, "Speed", "SpeedKmh", "Kmh")
            val rpm = number(x, "RPM", "Rpm")
            val gear = number(x, "Gear")
            val throttle = number(x, "Throttle")
            val brake = number(x, "Brake")
            val drs = x.optBoolean("DRS", x.optInt("DRS", 0) > 0)
            val lap = number(x, "Lap", "LapNumber")
            val driver = x.optString("RacingNumber").ifBlank { x.optString("Driver").ifBlank { key } }
            if(driver.isBlank()) continue
            out += app.f1multiview.model.DriverTelemetry(driver, speed, rpm, gear, throttle, brake, drs, lap, x.optString("LapTime").ifBlank { "-" })
        }
        if(out.isNotEmpty()) _telemetry.value = out.sortedBy { it.driver }
    }

    private fun number(x:JSONObject, vararg keys:String):Int {
        for(key in keys) {
            val raw = x.opt(key)
            when(raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    private fun parseRaceControl(data:JSONObject?) {
        val messages=data?.optJSONObject("Messages")?:data?.optJSONObject("messages")?:return
        val out=mutableListOf<RaceControlEvent>()
        val keys=messages.keys()
        while(keys.hasNext()){
            val m=messages.optJSONObject(keys.next())?:continue
            val message=m.optString("Message").ifBlank{m.optString("Category")}
            if(message.isNotBlank()) out+=RaceControlEvent(m.optString("Utc").ifBlank{m.optString("Time")}.ifBlank{"-"},message,m.optString("Flag").ifBlank{"INFO"})
        }
        if(out.isNotEmpty()) _raceControl.value=out.takeLast(30)
    }
    private fun parseLapCount(data:JSONObject?) {
        if(data==null)return
        val root=data.optJSONObject("LapCount") ?: data
        _lapCount.value=root.optInt("CurrentLap",0) to root.optInt("TotalLaps",0)
    }
    private fun parseWeather(data:JSONObject?) {
        if(data==null)return
        fun v(vararg n:String)=n.firstNotNullOfOrNull{data.optString(it).takeIf{v->v.isNotBlank()}}?:"-"
        _weather.value=TimingWeather(v("AirTemp","AirTemperature"),v("TrackTemp","TrackTemperature"),v("Humidity"),v("WindSpeed","Wind"),v("Rainfall","RainfallIntensity"),v("WindDirection"))
    }
    private fun parseTeamRadio(data:JSONObject?) {
        if(data==null)return
        val rows=mutableListOf<app.f1multiview.model.TeamRadioItem>()
        val items=data.optJSONArray("Captures") ?: data.optJSONArray("TeamRadio") ?: data.optJSONArray("Records")
        if(items!=null){
            for(i in 0 until items.length()){
                val x=items.optJSONObject(i) ?: continue
                val url=x.optString("Path").ifBlank{x.optString("Url").ifBlank{x.optString("URL")}}
                if(url.isBlank()) continue
                val driver=x.optString("RacingNumber").ifBlank{x.optString("Driver").ifBlank{x.optString("FullName")}}
                val time=x.optString("Utc").ifBlank{x.optString("Time")}.ifBlank{"-"}
                rows += app.f1multiview.model.TeamRadioItem(time,driver,url,x.optLong("Duration",0L))
            }
        } else {
            val keys=data.keys()
            while(keys.hasNext()){
                val key=keys.next()
                val x=data.optJSONObject(key) ?: continue
                val url=x.optString("Path").ifBlank{x.optString("Url").ifBlank{x.optString("URL")}}
                if(url.isNotBlank()) rows += app.f1multiview.model.TeamRadioItem(x.optString("Utc").ifBlank{"-"},x.optString("RacingNumber").ifBlank{key},url,x.optLong("Duration",0L))
            }
        }
        if(rows.isNotEmpty()) _teamRadio.value=rows.takeLast(20)
    }
    private fun sectorObject(lastLap:JSONObject?, line:JSONObject, key:String):JSONObject? {
        val direct=lastLap?.optJSONObject(key) ?: line.optJSONObject(key)
        if(direct!=null) return direct
        val index=key.removePrefix("Sector").toIntOrNull()?.minus(1) ?: return null
        val array=line.optJSONArray("Sectors")
        if(array!=null) return array.optJSONObject(index)
        val keyed=line.optJSONObject("Sectors") ?: return null
        return keyed.optJSONObject(index.toString())
    }

    private fun sectorSegments(lastLap:JSONObject?, line:JSONObject, key:String):List<String>{
        val obj=sectorObject(lastLap,line,key) ?: return emptyList()
        val segments=obj.optJSONArray("Segments") ?: obj.optJSONArray("segments")
        if(segments!=null) return buildList { for(i in 0 until segments.length()){ val s=segments.optJSONObject(i); val status=s?.optInt("Status",-1) ?: -1; add(segmentStatus(status)) } }
        val keyed=obj.optJSONObject("Segments") ?: obj.optJSONObject("segments") ?: return emptyList()
        return keyed.keys().asSequence().mapNotNull { keyed.optJSONObject(it)?.optInt("Status",-1) }.map(::segmentStatus).toList()
    }

    private fun segmentStatus(status:Int):String = when(status){2049->"GREEN";2051->"PURPLE";2048,2052->"YELLOW";2064->"BLUE";else->"GRAY"}

    private fun sectorValue(lastLap:JSONObject?, line:JSONObject, key:String):Pair<String,String>{
        val obj=sectorObject(lastLap,line,key)
        if(obj!=null){
            val value=obj.optString("Value").ifBlank{obj.optString("value")}.ifBlank{"-"}
            val status=when{obj.optBoolean("OverallFastest",false)->"PURPLE";obj.optBoolean("PersonalFastest",false)->"GREEN";else->"YELLOW"}
            return value to status
        }
        val raw=lastLap?.opt(key) ?: line.opt(key)
        val value=raw?.toString()?.takeIf{it.isNotBlank()} ?: "-"
        return value to "NORMAL"
    }

    private fun mergeTimingLines(incoming:JSONObject):JSONObject {
        synchronized(timingLock) {
            val keys=incoming.keys()
            while(keys.hasNext()){
                val number=keys.next()
                val patch=incoming.optJSONObject(number) ?: continue
                val existing=timingLinesState.optJSONObject(number)
                if(existing==null) timingLinesState.put(number, JSONObject(patch.toString()))
                else mergeJsonObject(existing, patch)
            }
            return JSONObject(timingLinesState.toString())
        }
    }
    private fun mergeJsonObject(target:JSONObject, patch:JSONObject) {
        val keys=patch.keys()
        while(keys.hasNext()){
            val key=keys.next()
            val value=patch.opt(key)
            if(value is JSONObject){
                val current=target.optJSONObject(key)
                if(current!=null) mergeJsonObject(current,value) else target.put(key,JSONObject(value.toString()))
            } else {
                target.put(key,value)
            }
        }
    }

    private fun parseTiming(data:JSONObject?){if(data==null)return;val incoming=data.optJSONObject("Lines")?:data.optJSONObject("lines")?:return;val lines=mergeTimingLines(incoming);if(lines.length()==0)return;val rows=mutableListOf<TimingRow>();val keys=lines.keys()
        while(keys.hasNext()){val number=keys.next();val line=lines.optJSONObject(number)?:continue;val pos=line.optString("Position").toIntOrNull()?:continue;val driver=line.optString("Tla").ifBlank{driverMeta[number]?.acronym ?: ""}.ifBlank{line.optString("FullName")}.ifBlank{line.optString("RacingNumber")}.ifBlank{"P"+pos};val leaderGap=line.optString("GapToLeader").ifBlank{"-"};val interval=line.optJSONObject("IntervalToPositionAhead")?.optString("Value").orEmpty().ifBlank{line.optString("IntervalToPositionAhead")}.ifBlank{"-"};val gap=leaderGap.ifBlank{interval};val lastObj=line.optJSONObject("LastLapTime");val bestObj=line.optJSONObject("BestLapTime");val last=lastObj?.optString("Value")?:line.optString("LastLapTime");val best=bestObj?.optString("Value")?:line.optString("BestLapTime");val tyre=bestObj?.optString("Compound")?:line.optString("Compound");val s1=sectorValue(lastObj,line,"Sector1");val s2=sectorValue(lastObj,line,"Sector2");val s3=sectorValue(lastObj,line,"Sector3");val speed=line.optString("Speed").ifBlank{line.optString("SpeedKmh")};val drs=line.optBoolean("DRS",line.optInt("DRS",0)>0);val lapNumber=line.optInt("Lap",0).takeIf{it>0}?:line.optInt("LapNumber",0);val seg1=sectorSegments(lastObj,line,"Sector1");val seg2=sectorSegments(lastObj,line,"Sector2");val seg3=sectorSegments(lastObj,line,"Sector3");rows+=TimingRow(pos,driver,gap,last.ifBlank{"-"},tyre.ifBlank{"-"},line.optInt("NumberOfPitStops",0),s1.first,s2.first,s3.first,speed.ifBlank{"-"},drs,best.ifBlank{"-"},lapNumber,s1.second,s2.second,s3.second,seg1,seg2,seg3,interval,leaderGap)}
        if(rows.isNotEmpty()){
            _rows.value=rows.sortedBy{it.position}
            val keys2=lines.keys()
            while(keys2.hasNext()){
                val number=keys2.next();val line=lines.optJSONObject(number) ?: continue
                timingMeta[number]=TimingMeta(
                    position=line.optString("Position").toIntOrNull()?:0,
                    speed=line.optString("Speed").ifBlank{line.optString("SpeedKmh")}.toIntOrNull()?:0,
                    lap=line.optInt("Lap",0).takeIf{it>0}?:line.optInt("LapNumber",0),
                    inPit=line.optBoolean("InPit",false),stopped=line.optBoolean("Stopped",false),retired=line.optBoolean("Retired",false)
                )
            }
            publishTrackPositions()
        }
    }
}
