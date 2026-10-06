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
    private var socket:WebSocket?=null;private var reconnect:Job?=null;private var keepAlive:Job?=null;@Volatile private var affinityCookie:String?=null
    fun start(){if(socket!=null||reconnect?.isActive==true)return;connect()}
    fun stop(){reconnect?.cancel();reconnect=null;keepAlive?.cancel();keepAlive=null;socket?.close(1000,"stop");socket=null;affinityCookie=null;_status.value="OFFLINE"}
    fun restart(){ stop(); start() }
    private fun connect(){scope.launch(Dispatchers.IO){try{
        _status.value="CONNECTING";affinityCookie=fetchAffinityCookie()?:affinityCookie
        val token=negotiate()?:throw IllegalStateException("Timing negotiation returned no connection token")
        val requestBuilder=Request.Builder().url(WS+java.net.URLEncoder.encode(token,"UTF-8")).apply{affinityCookie?.let{header("Cookie",it)}}.header("User-Agent","F1MultiView/1.0 Android")
        runCatching { authHeadersProvider() }.getOrDefault(emptyMap()).forEach { (key,value) -> requestBuilder.header(key,value) }
        val request=requestBuilder.build()
        socket=http.newWebSocket(request,Listener())
    }catch(_:Throwable){_status.value="RETRYING";scheduleReconnect()}}}
    private fun fetchAffinityCookie():String?{
        val req=Request.Builder().url("$BASE/signalrcore/negotiate").method("OPTIONS",null).header("User-Agent","F1MultiView/1.0 Android").build()
        return runCatching{http.newCall(req).execute().use{r->r.headers.values("Set-Cookie").firstNotNullOfOrNull{c->c.substringBefore(';').takeIf{it.startsWith("AWSALBCORS=",true)}}}}.getOrNull()
    }
    private fun negotiate():String?{
        val req=Request.Builder().url(NEGOTIATE).post("".toRequestBody("application/json".toMediaType())).header("User-Agent","F1MultiView/1.0 Android").apply{affinityCookie?.let{header("Cookie",it)}}.build()
        http.newCall(req).execute().use{r->if(!r.isSuccessful)return null;val json=JSONObject(r.body?.string().orEmpty());return json.optString("connectionToken").ifBlank{json.optString("connectionId")}.takeIf{it.isNotBlank()}}
    }
    private fun scheduleReconnect(){if(reconnect?.isActive==true)return;reconnect=scope.launch{delay(2000);if(isActive)connect()}}
    private inner class Listener:WebSocketListener(){
        override fun onOpen(ws:WebSocket,response:Response){_status.value="HANDSHAKING";ws.send("{\"protocol\":\"json\",\"version\":1}$RS")}
        override fun onMessage(ws:WebSocket,text:String){text.split(RS).filter{it.isNotBlank()}.forEach{frame->
            val json=runCatching{JSONObject(frame)}.getOrNull()?:return@forEach
            if(json.optString("error").isNotBlank()){_status.value="ERROR";ws.close(1002,json.optString("error"));return@forEach}
            if(_status.value=="HANDSHAKING"){_status.value="LIVE";val subscribe=JSONObject().put("type",1).put("invocationId","1").put("target","Subscribe").put("arguments",org.json.JSONArray().put(FEEDS.toTypedArray()));ws.send(subscribe.toString()+RS);keepAlive?.cancel();keepAlive=scope.launch{while(isActive){delay(KEEPALIVE_MS);if(socket===ws)ws.send(JSONObject().put("type",6).toString()+RS)}}}
            if(json.optInt("type")==1&&json.optString("target")=="feed"){val args=json.optJSONArray("arguments")?:return@forEach;if(args.length()>=2){val feed=args.optString(0);val data=decodeFeedObject(args.opt(1));when(feed){"TimingData"->parseTiming(data);"DriverList"->parseDriverList(data);"SessionInfo"->parseSessionInfo(data);"CarData.z","CarData"->parseCarData(data);"Position.z","Position"->parsePosition(data);"TrackStatus"->parseTrackStatus(data);"RaceControlMessages"->parseRaceControl(data);"WeatherData"->parseWeather(data);"TeamRadio"->parseTeamRadio(data)}}}
            if(json.optInt("type")==3){val result=json.optJSONObject("result")?:return@forEach;val it=result.keys();while(it.hasNext()){val feed=it.next();val data=decodeFeedObject(result.opt(feed));when(feed){"TimingData"->parseTiming(data);"DriverList"->parseDriverList(data);"SessionInfo"->parseSessionInfo(data);"CarData.z","CarData"->parseCarData(data);"Position.z","Position"->parsePosition(data);"TrackStatus"->parseTrackStatus(data);"RaceControlMessages"->parseRaceControl(data);"WeatherData"->parseWeather(data);"TeamRadio"->parseTeamRadio(data)}}}
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
    private data class TrackPositionRaw(val x:Double,val y:Double,val z:Double)

    private fun publishTrackPositions() {
        val now=System.currentTimeMillis()
        _trackPositions.value=positionMeta.map { (number,pos) ->
            val driver=driverMeta[number]
            val timing=timingMeta[number] ?: TimingMeta()
            TrackDriverPosition(number=number,name=driver?.name ?: number,acronym=driver?.acronym ?: number,team=driver?.team ?: "-",teamColor=driver?.teamColor ?: "FFFFFF",x=pos.x,y=pos.y,z=pos.z,position=timing.position,speed=timing.speed,lap=timing.lap,inPit=timing.inPit,stopped=timing.stopped,retired=timing.retired,updatedAtMs=now)
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
        val info = data.optJSONObject("SessionInfo") ?: data
        _sessionInfo.value = app.f1multiview.model.LiveSessionInfo(
            name = info.optString("Name").ifBlank { info.optString("MeetingName") }.ifBlank { "-" },
            meeting = info.optString("MeetingName").ifBlank { info.optString("Meeting").ifBlank { "-" } },
            country = info.optString("Country").ifBlank { info.optString("Location").ifBlank { "-" } },
            sessionType = info.optString("Type").ifBlank { info.optString("SessionType").ifBlank { "-" } },
            status = info.optString("Status").ifBlank { _status.value }
        )
    }

    private fun parseCarData(data:JSONObject?) {
        // CarData formats have changed over time. Accept the common per-driver object
        // representation without making the live timing connection dependent on it.
        parseTelemetryObject(data)
    }

    private fun parsePosition(data:JSONObject?) {
        parseTelemetryObject(data)
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
    private fun parseWeather(data:JSONObject?) {
        if(data==null)return
        fun v(vararg n:String)=n.firstNotNullOfOrNull{data.optString(it).takeIf{v->v.isNotBlank()}}?:"-"
        _weather.value=TimingWeather(v("AirTemp","AirTemperature"),v("TrackTemp","TrackTemperature"),v("Humidity"),v("WindSpeed","Wind"),v("Rainfall","RainfallIntensity"))
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
    private fun parseTiming(data:JSONObject?){if(data==null)return;val lines=data.optJSONObject("Lines")?:data.optJSONObject("lines")?:return;val rows=mutableListOf<TimingRow>();val keys=lines.keys()
        while(keys.hasNext()){val line=lines.optJSONObject(keys.next())?:continue;val pos=line.optString("Position").toIntOrNull()?:continue;val driver=line.optString("RacingNumber").ifBlank{line.optString("FullName")}.ifBlank{line.optString("Tla")}.ifBlank{"P"+pos};val gap=line.optString("GapToLeader").ifBlank{line.optString("IntervalToPositionAhead")}.ifBlank{"-"};val last=line.optJSONObject("LastLapTime")?.optString("Value")?:line.optString("LastLapTime");val tyre=line.optJSONObject("BestLapTime")?.optString("Compound")?:line.optString("Compound");val s1=line.optJSONObject("LastLapTime")?.optString("Sector1")?:line.optString("Sector1");val s2=line.optJSONObject("LastLapTime")?.optString("Sector2")?:line.optString("Sector2");val s3=line.optJSONObject("LastLapTime")?.optString("Sector3")?:line.optString("Sector3");val speed=line.optString("Speed").ifBlank{line.optString("SpeedKmh")};val drs=line.optBoolean("DRS",line.optInt("DRS",0)>0);rows+=TimingRow(pos,driver,gap,last.ifBlank{"-"},tyre.ifBlank{"-"},line.optInt("NumberOfPitStops",0),s1.ifBlank{"-"},s2.ifBlank{"-"},s3.ifBlank{"-"},speed.ifBlank{"-"},drs)}
        if(rows.isNotEmpty())_rows.value=rows.sortedBy{it.position}
    }
}
