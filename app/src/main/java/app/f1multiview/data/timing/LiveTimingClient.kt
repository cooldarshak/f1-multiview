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
import org.json.JSONArray
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class LiveTimingClient(private val scope:CoroutineScope, private val authHeadersProvider:suspend () -> Map<String,String> = { emptyMap() }){
    companion object{
        private const val BASE="https://livetiming.formula1.com"
        private const val NEGOTIATE="$BASE/signalrcore/negotiate?negotiateVersion=1"
        private const val WS="wss://livetiming.formula1.com/signalrcore?id="
        private const val RS='\u001e'
        private const val KEEPALIVE_MS=15000L
        private val FEEDS=listOf("SessionInfo","DriverList","TimingData","TimingAppData","TimingStats","CarData.z","Position.z","WeatherData","TrackStatus","RaceControlMessages","LapCount","TopThree","TeamRadio","ExtrapolatedClock")
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
    private val _sessionClock=MutableStateFlow("-")
    val sessionClock:StateFlow<String> = _sessionClock.asStateFlow()
    val trackStatus:StateFlow<TrackStatusInfo> = _trackStatus.asStateFlow()
    private val driverMeta=mutableMapOf<String,DriverMeta>()
    private val timingMeta=mutableMapOf<String,TimingMeta>()
    private val positionMeta=mutableMapOf<String,TrackPositionRaw>()
    // F1 SignalR sends TimingData as differential updates. Keep the latest merged Lines state.
    private val timingLinesState=JSONObject()
    private val timingLock=Any()
    private val telemetryByDriver = linkedMapOf<String, DriverTelemetry>()
    private val observedFeeds = mutableSetOf<String>()
    private var lastFeedSummaryAtMs = 0L
    private var socket:WebSocket?=null;private var reconnect:Job?=null;private var keepAlive:Job?=null;@Volatile private var affinityCookie:String?=null
    fun start(){if(socket!=null||reconnect?.isActive==true)return;connect()}
    fun stop(){reconnect?.cancel();reconnect=null;keepAlive?.cancel();keepAlive=null;socket?.close(1000,"stop");socket=null;affinityCookie=null;_status.value="OFFLINE"}
    fun restart(){ stop(); start() }
    // The public LiveTiming SignalR service is anonymous. F1TV playback bearer tokens belong to
    // a different backend and must not be sent to livetiming.formula1.com.
    private fun connect(){scope.launch(Dispatchers.IO){
        try{
            _status.value="CONNECTING"
            affinityCookie=fetchAffinityCookie()?:affinityCookie
            val token=negotiate(affinityCookie)?:throw IllegalStateException("Timing negotiation returned no connection token")
            val requestBuilder=Request.Builder()
                .url(WS+java.net.URLEncoder.encode(token,"UTF-8"))
                .header("User-Agent","BestHTTP")
                .apply{affinityCookie?.let{header("Cookie",it)}}
            AppLogger.i("LiveTiming","SIGNALR_CONNECTING affinityCookiePresent=${!affinityCookie.isNullOrBlank()}")
            socket=http.newWebSocket(requestBuilder.build(),Listener())
        }catch(failure:Throwable){
            AppLogger.w("LiveTiming","SIGNALR_CONNECT_FAILED reason=${failure.javaClass.simpleName}")
            _status.value="RETRYING"
            scheduleReconnect()
        }
    }}
    private fun fetchAffinityCookie():String? {
        val request=Request.Builder()
            .url("$BASE/signalrcore/negotiate")
            .method("OPTIONS",null)
            .header("User-Agent","BestHTTP")
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                response.headers.values("Set-Cookie")
                    .firstNotNullOfOrNull { value ->
                        value.substringBefore(';').takeIf{it.startsWith("AWSALBCORS=",true)}
                    }
            }
        }.getOrNull()
    }
    private fun negotiate(cookie:String?):String? {
        val requestBuilder=Request.Builder()
            .url(NEGOTIATE)
            .post("".toRequestBody("application/json".toMediaType()))
            .header("User-Agent","BestHTTP")
            .apply{cookie?.let{header("Cookie",it)}}
        http.newCall(requestBuilder.build()).execute().use { response ->
            if(!response.isSuccessful){
                AppLogger.w("LiveTiming","SIGNALR_NEGOTIATE_FAILED httpStatus=${response.code}")
                return null
            }
            val json=JSONObject(response.body?.string().orEmpty())
            val token=json.optString("connectionToken").ifBlank{json.optString("connectionId")}.takeIf{it.isNotBlank()}
            AppLogger.i("LiveTiming","SIGNALR_NEGOTIATED tokenPresent=${!token.isNullOrBlank()}")
            return token
        }
    }
    private fun scheduleReconnect(){if(reconnect?.isActive==true)return;reconnect=scope.launch{delay(2000);if(isActive)connect()}}
    private inner class Listener:WebSocketListener(){
        override fun onOpen(ws:WebSocket,response:Response){
            _status.value="HANDSHAKING"
            AppLogger.i("LiveTiming","SIGNALR_WEBSOCKET_OPEN httpStatus=${response.code}")
            ws.send("{\"protocol\":\"json\",\"version\":1}$RS")
        }
        override fun onMessage(ws:WebSocket,text:String){
            text.split(RS).filter{it.isNotBlank()}.forEach{frame->
                val json=runCatching{JSONObject(frame)}.getOrNull()?:return@forEach
                if(json.optString("error").isNotBlank()){
                    AppLogger.w("LiveTiming","SIGNALR_SERVER_ERROR")
                    _status.value="ERROR"
                    ws.close(1002,"SignalR error")
                    return@forEach
                }
                // The first post-handshake frame is the SignalR handshake acknowledgement.
                if(_status.value=="HANDSHAKING"){
                    _status.value="LIVE"
                    val feedArray=JSONArray()
                    FEEDS.forEach(feedArray::put)
                    val subscribe=JSONObject()
                        .put("type",1)
                        .put("invocationId","1")
                        .put("target","Subscribe")
                        .put("arguments",JSONArray().put(feedArray))
                    ws.send(subscribe.toString()+RS)
                    AppLogger.i("LiveTiming","SIGNALR_SUBSCRIBE_SENT feedCount=${FEEDS.size}")
                    keepAlive?.cancel()
                    keepAlive=scope.launch{
                        while(isActive){
                            delay(KEEPALIVE_MS)
                            if(socket===ws)ws.send(JSONObject().put("type",6).toString()+RS)
                        }
                    }
                }
                if(json.optInt("type")==1 && json.optString("target")=="feed"){
                    val args=json.optJSONArray("arguments")?:return@forEach
                    if(args.length()>=2) onFeedPayload(args.optString(0),decodeFeedObject(args.opt(1)))
                }
                if(json.optInt("type")==3){
                    val result=json.optJSONObject("result")?:return@forEach
                    val it=result.keys()
                    while(it.hasNext()){
                        val feed=it.next()
                        onFeedPayload(feed,decodeFeedObject(result.opt(feed)))
                    }
                }
            }
        }
        private fun onFeedPayload(feed:String,data:JSONObject?){
            if(data==null){
                if(observedFeeds.add("$feed:decode-failed")) AppLogger.w("LiveTiming","FEED_DECODE_FAILED feed=$feed")
                return
            }
            if(observedFeeds.add(feed)) AppLogger.i("LiveTiming","FEED_FIRST_PAYLOAD feed=$feed keys=${data.length()}")
            when(feed){
                "TimingData","TimingDataF1"->parseTiming(data)
                "TimingAppData"->parseTimingAppData(data)
                "DriverList"->parseDriverList(data)
                "SessionInfo"->parseSessionInfo(data)
                "CarData.z","CarData"->parseCarData(data)
                "Position.z","Position"->parsePosition(data)
                "TrackStatus"->parseTrackStatus(data)
                "RaceControlMessages"->parseRaceControl(data)
                "WeatherData"->parseWeather(data)
                "LapCount"->parseLapCount(data)
                "TeamRadio"->parseTeamRadio(data)
                "ExtrapolatedClock"->parseExtrapolatedClock(data)
            }
            val now=System.currentTimeMillis()
            if(now-lastFeedSummaryAtMs>=10_000L){
                lastFeedSummaryAtMs=now
                AppLogger.i("LiveTiming","FEED_SUMMARY timingRows=${_rows.value.size} gpsDrivers=${positionMeta.size} telemetryDrivers=${telemetryByDriver.size} status=${_status.value}")
            }
        }
        override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){
            if(socket===ws)socket=null
            keepAlive?.cancel();keepAlive=null
            AppLogger.w("LiveTiming","SIGNALR_WEBSOCKET_FAILED reason=${t.javaClass.simpleName} httpStatus=${response?.code ?: -1}")
            _status.value="RETRYING"
            scheduleReconnect()
        }
        override fun onClosed(ws:WebSocket,code:Int,reason:String){
            if(socket===ws)socket=null
            keepAlive?.cancel();keepAlive=null
            AppLogger.w("LiveTiming","SIGNALR_WEBSOCKET_CLOSED code=$code")
            if(code!=1000){_status.value="RETRYING";scheduleReconnect()}else _status.value="OFFLINE"
        }
    }
    private fun decodeFeedObject(raw:Any?):JSONObject? {
        if(raw is JSONObject)return raw
        if(raw is JSONArray)return JSONObject().put("Position",raw)
        val encoded=raw as? String ?: return null
        if(encoded.isBlank())return null
        jsonObjectFromText(encoded)?.let{return it}
        val bytes=runCatching{Base64.decode(encoded,Base64.DEFAULT)}.getOrNull() ?: return null
        inflateFeedBytes(bytes)?.let{jsonObjectFromText(it)?.let{return it}}
        // Some feed messages are base64-wrapped JSON rather than raw-deflate compressed JSON.
        return jsonObjectFromText(runCatching{String(bytes,Charsets.UTF_8)}.getOrDefault(""))
    }
    private fun jsonObjectFromText(text:String):JSONObject? {
        val cleaned=text.trimStart('\uFEFF',' ','\n','\r','\t')
        val parsed=runCatching{JSONTokener(cleaned).nextValue()}.getOrNull()
        return when(parsed){
            is JSONObject->parsed
            is JSONArray->JSONObject().put("Position",parsed)
            else->null
        }
    }
    private fun inflateFeedBytes(bytes:ByteArray):String? {
        val inflater=Inflater(true)
        return try {
            inflater.setInput(bytes)
            val output=ByteArrayOutputStream()
            val buffer=ByteArray(16*1024)
            var iterations=0
            while(!inflater.finished() && iterations++<2048){
                val count=inflater.inflate(buffer)
                if(count>0)output.write(buffer,0,count)
                else if(inflater.needsInput()||inflater.needsDictionary())break
            }
            output.toString(Charsets.UTF_8.name()).takeIf{it.isNotBlank()}
        }catch(_:Throwable){null}finally{inflater.end()}
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
        val circuit=meeting.optJSONObject("Circuit") ?: info.optJSONObject("Circuit") ?: JSONObject()
        val year=info.optString("StartDate").takeIf{it.length>=4}?.take(4)?.toIntOrNull()
            ?: info.optString("EndDate").takeIf{it.length>=4}?.take(4)?.toIntOrNull()
        val countryObj=meeting.optJSONObject("Country") ?: JSONObject()
        val keyRaw=circuit.opt("Key") ?: circuit.opt("CircuitKey") ?: meeting.opt("CircuitKey")
        val circuitKey=when(keyRaw){
            is Number->keyRaw.toInt().takeIf{it>0}
            is String->keyRaw.toIntOrNull()?.takeIf{it>0}
            else->null
        } ?: meeting.optInt("CircuitKey",0).takeIf{it>0}
        val name=info.optString("Name").ifBlank{info.optString("MeetingName")}.ifBlank{"-"}
        val meetingName=info.optString("MeetingName").ifBlank{meeting.optString("Name")}.ifBlank{name}
        val circuitName=circuit.optString("Name").ifBlank{circuit.optString("ShortName")}.ifBlank{meeting.optString("Location")}
        _sessionInfo.value=LiveSessionInfo(
            name=name,
            meeting=meetingName,
            country=countryObj.optString("Name").ifBlank{countryObj.optString("Code")}.ifBlank{info.optString("Country")}.ifBlank{info.optString("Location")}.ifBlank{"-"},
            sessionType=info.optString("Type").ifBlank{info.optString("SessionType")}.ifBlank{"-"},
            status=info.optString("Status").ifBlank{_status.value},
            circuitKey=circuitKey,
            year=year,
            circuitName=circuitName
        )
        AppLogger.i("LiveTiming","SESSION_INFO yearPresent=${year!=null} circuitKeyPresent=${circuitKey!=null} circuitNamePresent=${circuitName.isNotBlank()}")
    }

    private fun parseCarData(data:JSONObject?) {
        if(data==null)return
        var updated=false
        fun consumeCars(cars:JSONObject){
            val keys=cars.keys()
            while(keys.hasNext()){
                val driverNumber=keys.next()
                val car=cars.optJSONObject(driverNumber) ?: continue
                val channels=car.optJSONObject("Channels") ?: car
                val previous=telemetryByDriver[driverNumber]
                fun channel(index:String,vararg names:String):Int {
                    val indexed=channels.opt(index)
                    when(indexed){
                        is Number->return indexed.toInt()
                        is String->indexed.toIntOrNull()?.let{return it}
                    }
                    return number(channels,*names)
                }
                telemetryByDriver[driverNumber]=DriverTelemetry(
                    driver=driverNumber,
                    speed=channel("2","Speed","SpeedKmh","Kmh"),
                    rpm=channel("0","RPM","Rpm"),
                    gear=channel("3","Gear"),
                    throttle=channel("4","Throttle"),
                    brake=channel("5","Brake"),
                    lap=previous?.lap ?: timingMeta[driverNumber]?.lap ?: 0,
                    lapTime=previous?.lapTime ?: "-"
                )
                updated=true
            }
        }
        fun visit(value:Any?,depth:Int=0){
            if(depth>7 || value==null)return
            when(value){
                is JSONObject -> {
                    val cars=value.optJSONObject("Cars")
                    if(cars!=null)consumeCars(cars)
                    else {
                        val channels=value.optJSONObject("Channels")
                        val driver=value.optString("RacingNumber").ifBlank{value.optString("Driver")}
                        if(channels!=null && driver.isNotBlank()) consumeCars(JSONObject().put(driver,value))
                        else {
                            val keys=value.keys()
                            while(keys.hasNext()){
                                val child=value.opt(keys.next())
                                if(child is JSONObject || child is JSONArray)visit(child,depth+1)
                            }
                        }
                    }
                }
                is JSONArray -> for(i in 0 until value.length())visit(value.opt(i),depth+1)
            }
        }
        visit(data)
        if(updated)_telemetry.value=telemetryByDriver.values.sortedBy{it.driver}
    }

    private fun parsePosition(data:JSONObject?) {
        if(data==null)return
        var changed=false
        fun numeric(value:Any?):Double=when(value){
            is Number->value.toDouble()
            is String->value.toDoubleOrNull() ?: Double.NaN
            else->Double.NaN
        }
        fun looksLikeEntries(entries:JSONObject):Boolean {
            val keys=entries.keys()
            while(keys.hasNext()){
                val p=entries.optJSONObject(keys.next()) ?: continue
                if(numeric(p.opt("X")).isFinite() && numeric(p.opt("Y")).isFinite())return true
            }
            return false
        }
        fun applyEntries(entries:JSONObject){
            val keys=entries.keys()
            while(keys.hasNext()){
                val number=keys.next()
                val p=entries.optJSONObject(number) ?: continue
                val x=numeric(p.opt("X"));val y=numeric(p.opt("Y"))
                if(!x.isFinite()||!y.isFinite())continue
                val z=numeric(p.opt("Z")).takeIf{it.isFinite()} ?: 0.0
                val previous=positionMeta[number]
                val moved=previous==null || kotlin.math.abs(previous.x-x)>0.01 || kotlin.math.abs(previous.y-y)>0.01
                val trail=when {
                    previous==null->listOf(TrackPoint(x,y))
                    moved->(previous.trail+TrackPoint(x,y)).takeLast(12)
                    else->previous.trail
                }
                positionMeta[number]=TrackPositionRaw(x,y,z,trail)
                changed=changed||moved
            }
        }
        fun visit(value:Any?,depth:Int=0){
            if(depth>8||value==null)return
            when(value){
                is JSONArray->for(i in 0 until value.length())visit(value.opt(i),depth+1)
                is JSONObject->{
                    val entries=value.optJSONObject("Entries")
                    if(entries!=null&&looksLikeEntries(entries)){applyEntries(entries);return}
                    if(looksLikeEntries(value)){applyEntries(value);return}
                    val position=value.opt("Position")
                    if(position is JSONArray||position is JSONObject)visit(position,depth+1)
                    else {
                        val keys=value.keys()
                        while(keys.hasNext()){
                            val child=value.opt(keys.next())
                            if(child is JSONObject||child is JSONArray)visit(child,depth+1)
                        }
                    }
                }
            }
        }
        visit(data)
        if(changed)publishTrackPositions()
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
            val lap = number(x, "Lap", "LapNumber")
            val driver = x.optString("RacingNumber").ifBlank { x.optString("Driver").ifBlank { key } }
            if(driver.isBlank()) continue
            out += app.f1multiview.model.DriverTelemetry(driver, speed, rpm, gear, throttle, brake, lap, x.optString("LapTime").ifBlank { "-" })
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
    private fun parseExtrapolatedClock(data:JSONObject?) {
        if(data==null)return
        val root=data.optJSONObject("ExtrapolatedClock") ?: data
        val remaining=root.optString("Remaining").ifBlank{root.optString("remaining")}
        if(remaining.isNotBlank()) _sessionClock.value=remaining
    }

    private fun parseLapCount(data:JSONObject?) {
        if(data==null)return
        val root=data.optJSONObject("LapCount") ?: data
        _lapCount.value=root.optInt("CurrentLap",0) to root.optInt("TotalLaps",0)
    }
    private fun parseWeather(data:JSONObject?) {
        if(data==null)return
        val root=data.optJSONObject("WeatherData") ?: data
        fun v(vararg n:String)=n.firstNotNullOfOrNull{root.optString(it).takeIf{value->value.isNotBlank() && value!="null"}}?:"-"
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

    private fun segmentStatus(status:Int):String = when(status){
        2048->"YELLOW"
        2049->"GREEN"
        2051->"PURPLE"
        2064->"BLUE"
        else->"GRAY"
    }

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

    /**
     * TimingAppData is the authoritative live source for compound/stint and pit-stop
     * information. It is a delta feed too, so merge the incoming driver lines before
     * selecting the newest stint. Never infer tyre compound from BestLapTime.
     */
    private val timingAppLinesState = JSONObject()
    private val timingAppLock = Any()
    private val tyreByDriver = mutableMapOf<String, String>()
    private val pitStopsByDriver = mutableMapOf<String, Int>()

    private fun parseTimingAppData(data: JSONObject?) {
        if (data == null) return
        val incoming = data.optJSONObject("Lines") ?: data.optJSONObject("lines") ?: return
        synchronized(timingAppLock) {
            val keys = incoming.keys()
            while (keys.hasNext()) {
                val number = keys.next()
                val patch = incoming.optJSONObject(number) ?: continue
                val current = timingAppLinesState.optJSONObject(number)
                if (current == null) timingAppLinesState.put(number, JSONObject(patch.toString()))
                else mergeJsonObject(current, patch)
            }
            val latest = JSONObject(timingAppLinesState.toString())
            val tyres = mutableMapOf<String, String>()
            val pitStops = mutableMapOf<String, Int>()
            val appKeys = latest.keys()
            while (appKeys.hasNext()) {
                val number = appKeys.next()
                val line = latest.optJSONObject(number) ?: continue
                val stints = line.optJSONObject("Stints")
                val stintArray = line.optJSONArray("Stints")
                var newest: JSONObject? = null
                if (stints != null) {
                    val stintKeys = stints.keys().asSequence().toList()
                    val newestKey = stintKeys.maxByOrNull { it.toIntOrNull() ?: -1 }
                    newest = newestKey?.let(stints::optJSONObject)
                } else if (stintArray != null && stintArray.length() > 0) {
                    newest = stintArray.optJSONObject(stintArray.length() - 1)
                } else {
                    newest = line.optJSONObject("Stint")
                }
                val compound = newest?.optString("Compound").orEmpty()
                    .ifBlank { newest?.optString("compound").orEmpty() }
                if (compound.isNotBlank() && compound != "null") { tyres[number] = compound.uppercase(); tyreByDriver[number] = compound.uppercase() }
                val count = line.optInt("NumberOfPitStops", line.optInt("PitStops", -1))
                if (count >= 0) { pitStops[number] = count; pitStopsByDriver[number] = count }
                val old = timingMeta[number] ?: TimingMeta()
                timingMeta[number] = old.copy(
                    inPit = line.optBoolean("InPit", old.inPit) || line.optBoolean("PitIn", false),
                    stopped = line.optBoolean("Stopped", old.stopped)
                )
            }
            if (tyres.isNotEmpty() || pitStops.isNotEmpty()) {
                _rows.value = _rows.value.map { row ->
                    val number = row.driverNumber.ifBlank {
                        driverMeta.entries.firstOrNull { it.value.acronym.equals(row.driver, true) }?.key.orEmpty()
                    }
                    row.copy(
                        tyre = tyres[number] ?: tyreByDriver[number] ?: row.tyre,
                        pitStops = pitStops[number] ?: pitStopsByDriver[number] ?: row.pitStops
                    )
                }
            }
        }
        publishTrackPositions()
    }

    private fun parseTiming(data:JSONObject?){if(data==null)return;val incoming=data.optJSONObject("Lines")?:data.optJSONObject("lines")?:return;val lines=mergeTimingLines(incoming);if(lines.length()==0)return;val rows=mutableListOf<TimingRow>();val keys=lines.keys()
        while(keys.hasNext()){val number=keys.next();val line=lines.optJSONObject(number)?:continue;val pos=line.optString("Position").toIntOrNull()?:continue;val driver=line.optString("Tla").ifBlank{driverMeta[number]?.acronym ?: ""}.ifBlank{line.optString("FullName")}.ifBlank{line.optString("RacingNumber")}.ifBlank{"P"+pos};val leaderGap=line.optString("GapToLeader").ifBlank{"-"};val interval=line.optJSONObject("IntervalToPositionAhead")?.optString("Value").orEmpty().ifBlank{line.optString("IntervalToPositionAhead")}.ifBlank{"-"};val gap=if(pos==1)"LEADER" else interval.takeIf{it.isNotBlank()&&it!="-"} ?: leaderGap;val lastObj=line.optJSONObject("LastLapTime");val bestObj=line.optJSONObject("BestLapTime");val last=lastObj?.optString("Value").orEmpty().ifBlank{line.optString("LastLapTime")};val best=bestObj?.optString("Value").orEmpty().ifBlank{line.optString("BestLapTime")};val tyre=line.optString("Compound").ifBlank{"-"};val s1=sectorValue(lastObj,line,"Sector1");val s2=sectorValue(lastObj,line,"Sector2");val s3=sectorValue(lastObj,line,"Sector3");val speed=line.optString("Speed").ifBlank{line.optString("SpeedKmh")};val lapNumber=line.optInt("Lap",0).takeIf{it>0}?:line.optInt("LapNumber",0);val seg1=sectorSegments(lastObj,line,"Sector1");val seg2=sectorSegments(lastObj,line,"Sector2");val seg3=sectorSegments(lastObj,line,"Sector3");rows+=TimingRow(pos,driver,gap,last.ifBlank{"-"},tyreByDriver[number] ?: tyre.ifBlank{"-"},pitStopsByDriver[number] ?: line.optInt("NumberOfPitStops",0),s1.first,s2.first,s3.first,speed.ifBlank{"-"},best.ifBlank{"-"},lapNumber,s1.second,s2.second,s3.second,seg1,seg2,seg3,interval,leaderGap,driverNumber=number)}
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
