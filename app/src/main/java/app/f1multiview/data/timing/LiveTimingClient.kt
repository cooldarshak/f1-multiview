package app.f1multiview.data.timing

import app.f1multiview.model.TimingRow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LiveTimingClient(private val scope:CoroutineScope){
    companion object{
        private const val BASE="https://livetiming.formula1.com"
        private const val NEGOTIATE="$BASE/signalrcore/negotiate?negotiateVersion=1"
        private const val WS="wss://livetiming.formula1.com/signalrcore?id="
        private const val RS='\u001e'
        private const val KEEPALIVE_MS=15000L
        private val FEEDS=listOf("SessionInfo","DriverList","TimingData","TimingAppData","TimingStats","WeatherData","TrackStatus","RaceControlMessages","LapCount","TopThree")
    }
    private val http=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).build()
    private val _rows=MutableStateFlow<List<TimingRow>>(emptyList());val rows: StateFlow<List<TimingRow>> = _rows.asStateFlow()
    private val _status=MutableStateFlow("OFFLINE");val status:StateFlow<String> = _status.asStateFlow()
    private var socket:WebSocket?=null;private var reconnect:Job?=null;private var keepAlive:Job?=null;@Volatile private var affinityCookie:String?=null
    fun start(){if(socket!=null||reconnect?.isActive==true)return;connect()}
    fun stop(){reconnect?.cancel();reconnect=null;keepAlive?.cancel();keepAlive=null;socket?.close(1000,"stop");socket=null;affinityCookie=null;_status.value="OFFLINE"}
    private fun connect(){scope.launch(Dispatchers.IO){try{
        _status.value="CONNECTING";affinityCookie=fetchAffinityCookie()?:affinityCookie
        val token=negotiate()?:throw IllegalStateException("Timing negotiation returned no connection token")
        val request=Request.Builder().url(WS+java.net.URLEncoder.encode(token,"UTF-8")).apply{affinityCookie?.let{header("Cookie",it)}}.header("User-Agent","F1MultiView/1.0 Android").build()
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
            if(_status.value=="HANDSHAKING"){_status.value="LIVE";val subscribe=JSONObject().put("type",1).put("invocationId","1").put("target","Subscribe").put("arguments",org.json.JSONArray().put(org.json.JSONArray(FEEDS)));ws.send(subscribe.toString()+RS);keepAlive?.cancel();keepAlive=scope.launch{while(isActive){delay(KEEPALIVE_MS);if(socket===ws)ws.send(JSONObject().put("type",6).toString()+RS)}}}
            if(json.optInt("type")==1&&json.optString("target")=="feed"){val args=json.optJSONArray("arguments")?:return@forEach;if(args.length()>=2&&args.optString(0)=="TimingData")parseTiming(args.optJSONObject(1))}
        }}
        override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){if(socket===ws)socket=null;keepAlive?.cancel();keepAlive=null;_status.value="RETRYING";scheduleReconnect()}
        override fun onClosed(ws:WebSocket,code:Int,reason:String){if(socket===ws)socket=null;keepAlive?.cancel();keepAlive=null;if(code!=1000){_status.value="RETRYING";scheduleReconnect()}else _status.value="OFFLINE"}
    }
    private fun parseTiming(data:JSONObject?){if(data==null)return;val lines=data.optJSONObject("Lines")?:data.optJSONObject("lines")?:return;val rows=mutableListOf<TimingRow>();val keys=lines.keys()
        while(keys.hasNext()){val line=lines.optJSONObject(keys.next())?:continue;val pos=line.optString("Position").toIntOrNull()?:continue;val driver=line.optString("RacingNumber").ifBlank{line.optString("FullName")}.ifBlank{line.optString("Tla")}.ifBlank{"P"+pos};val gap=line.optString("GapToLeader").ifBlank{line.optString("IntervalToPositionAhead")}.ifBlank{"-"};val last=line.optJSONObject("LastLapTime")?.optString("Value")?:line.optString("LastLapTime");val tyre=line.optJSONObject("BestLapTime")?.optString("Compound")?:line.optString("Compound");rows+=TimingRow(pos,driver,gap,last.ifBlank{"-"},tyre.ifBlank{"-"},line.optInt("NumberOfPitStops",0))}
        if(rows.isNotEmpty())_rows.value=rows.sortedBy{it.position}
    }
}
