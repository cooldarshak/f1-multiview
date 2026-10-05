package app.f1multiview.data.f1tv

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import app.f1multiview.core.auth.SessionStore
import app.f1multiview.core.playback.*
import app.f1multiview.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class AuthorizedF1TvGateway(private val context: Context) : PlaybackGateway {
    private val api=F1TvApiClient()
    private val store=SessionStore(context)
    override suspend fun signIn(credentials: ProviderCredentials): Result<Unit> =runCatching{api.login(credentials.username,credentials.password);api.authHeaders()["ascendontoken"]?.let(store::put)}
    override suspend fun signInWithSessionToken(token: String): Result<Unit> =runCatching{api.initialize(token);api.authHeaders()["ascendontoken"]?.let(store::put)}
    override suspend fun restoreSession(): Result<Boolean> = runCatching {
        val saved = store.get()
        if (!saved.isNullOrBlank() && !api.isTokenExpired(saved)) {
            val restored = runCatching { api.initialize(saved) }.isSuccess
            if (restored && api.isAuthenticated()) return@runCatching true
        }
        val refreshed = refreshFromBrowserSession()
        if (refreshed.isNullOrBlank()) return@runCatching false
        api.initialize(refreshed)
        store.put(refreshed)
        api.isAuthenticated()
    }

    override suspend fun signOut() {
        api.clear()
        store.clear()
        withContext(Dispatchers.Main.immediate) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    private suspend fun refreshFromBrowserSession(): String? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            var finished = false
            var webView: WebView? = null
            val handler = Handler(Looper.getMainLooper())
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)

            fun finish(token: String?) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                webView?.stopLoading()
                webView?.destroy()
                continuation.resume(token)
            }

            fun poll() {
                val token = api.subscriptionTokenFromWebViewCookies()
                if (!token.isNullOrBlank()) {
                    cookies.flush()
                    finish(token)
                } else {
                    handler.postDelayed({ poll() }, 700L)
                }
            }

            webView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = F1TvApiClient.BROWSER_UA
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        poll()
                    }
                }
                loadUrl("https://account.formula1.com/")
            }

            handler.postDelayed({ finish(null) }, 20_000L)
            poll()
            continuation.invokeOnCancellation { handler.post { finish(null) } }
        }
    }
    override suspend fun sessions():Result<List<Session>> = runCatching {
        val out=mutableListOf<Session>(); val items=api.liveNow().optJSONObject("resultObj")?.optJSONArray("items")?:org.json.JSONArray()
        for(i in 0 until items.length()){val item=items.optJSONObject(i)?:continue;val meta=item.optJSONObject("metadata")?:continue;val id=meta.optString("contentId").takeIf{it.isNotBlank()}?:continue;val title=meta.optString("title").ifBlank{meta.optJSONObject("emfAttributes")?.optString("Global_Title")?:"Live"};out+=Session(id,title,"LIVE","Live now",true,artworkUrl=pictureUrl(meta.optString("pictureUrl"), 640, 360), backgroundArtworkUrl=pictureUrl(meta.optString("pictureUrl"), 1920, 1080))}
        out
    }
    override suspend fun vodSeasons():Result<List<VodSeason>> = runCatching {
        val archive=api.fetchPage(493);val map=linkedMapOf<Int,Int>();val legacy=mapOf(12343 to 2026,10295 to 2025,8192 to 2024,6603 to 2023,4319 to 2022,1510 to 2021,392 to 2020,2128 to 2019,2130 to 2018)
        for(i in 0 until archive.length()){val c=archive.optJSONObject(i)?:continue;val subs=c.optJSONObject("retrieveItems")?.optJSONArray("containers")?:continue
            for(j in 0 until subs.length()){val s=subs.optJSONObject(j)?:continue;val actions=s.optJSONArray("actions")?:continue;var pageId:Int?=null
                for(k in 0 until actions.length()){val a=actions.optJSONObject(k)?:continue;val m=Regex("/PAGE/(\\d+)/").find(a.optString("uri"));if(a.optString("key")=="onClick"&&m!=null){pageId=m.groupValues[1].toIntOrNull();break}}
                val text=listOf(s.optString("title"),s.optJSONObject("metadata")?.optString("title"),s.optJSONObject("metadata")?.optString("plainText")).joinToString(" ")
                val year=Regex("\\b(19\\d{2}|20\\d{2})\\b").find(text)?.value?.toIntOrNull()?:pageId?.let(legacy::get)
                if(pageId!=null&&year!=null&&year in 2018..java.time.Year.now().value)map[year]=pageId
            }
        }
        legacy.forEach{(page,year)->if(year in 2018..java.time.Year.now().value)map.putIfAbsent(year,page)}
        map.entries.sortedByDescending{it.key}.map{VodSeason(it.key,it.value)}
    }
    override suspend fun vodEvents(season:VodSeason):Result<List<VodEvent>> = runCatching {
        val root=api.fetchPage(season.pageId); val map=linkedMapOf<Int,VodEvent>(); val now=System.currentTimeMillis()
        val nodes=flattenObjects(root)
        for(s in nodes){
            val actions=s.optJSONArray("actions")?:continue
            var pageId:Int?=null
            for(k in 0 until actions.length()){
                val a=actions.optJSONObject(k)?:continue
                val uri=a.optString("uri")
                val m=Regex("/PAGE/(\\d+)/",RegexOption.IGNORE_CASE).find(uri)
                if(m!=null){pageId=m.groupValues[1].toIntOrNull();if(a.optString("key").equals("onClick",true))break}
            }
            if(pageId==null)continue
            val meta=s.optJSONObject("metadata")?:org.json.JSONObject()
            val emf=meta.optJSONObject("emfAttributes")?:org.json.JSONObject()
            val props=s.optJSONArray("properties")?.optJSONObject(0)?:org.json.JSONObject()
            val title=listOf(s.optString("title"),meta.optString("title"),meta.optString("plainText"),emf.optString("Meeting_Name"))
                .firstOrNull{it.isNotBlank()}?.trim().orEmpty()
            val upper=title.uppercase()
            val looksLikeMeeting=upper.contains("GRAND PRIX")||upper.contains("FORMULA 1")||
                emf.optString("VideoType").equals("meetings",true)||meta.optString("contentSubtype").equals("MEETING",true)||
                meta.optString("contentType").contains("meeting",true)
            if(!looksLikeMeeting||upper.contains(" F2 ")||upper.contains(" F3 "))continue
            val rawDate=listOf(
                props.optString("meeting_Start_Date"), props.optString("meetingStartDate"),
                meta.optString("meeting_Start_Date"), meta.optString("meetingStartDate")
            ).firstOrNull{it.isNotBlank()}
            val startDate=rawDate?.toLongOrNull() ?: 0L
            if(startDate>now)continue
            val number=props.optInt("meeting_Number",emf.optInt("Meeting_Number",0))
            map.putIfAbsent(pageId,VodEvent(pageId,title,number,season.year,upper.contains("TEST"),pictureUrl(meta.optString("pictureUrl"), 640, 360), pictureUrl(meta.optString("pictureUrl"), 1920, 1080)))
        }
        map.values.sortedWith(compareBy<VodEvent>{it.meetingNumber==0}.thenBy{it.meetingNumber}.thenBy{it.meetingName})
    }
    override suspend fun vodSessions(event:VodEvent):Result<List<VodSession>> = runCatching {
        val root=api.fetchPage(event.pageId); val out=mutableListOf<VodSession>()
        for(s in flattenObjects(root)){
            val meta=s.optJSONObject("metadata")?:continue
            val emf=meta.optJSONObject("emfAttributes")?:org.json.JSONObject()
            val contentId=meta.optString("contentId").takeIf{it.isNotBlank()}?:continue
            val subtype=meta.optString("contentSubtype")
            val videoType=emf.optString("VideoType")
            val title=s.optString("title").ifBlank{meta.optString("title")}.trim()
            val looksLikeSession=videoType.equals("meetingSession",true)||
                title.contains("race",true)||title.contains("qualifying",true)||title.contains("practice",true)||
                title.contains("sprint",true)
            if(!looksLikeSession)continue
            out+=VodSession(contentId,title,mapSessionType(subtype,videoType,title),normalizeSeries(emf.optString("Series")),event.pageId,pictureUrl(meta.optString("pictureUrl"), 640, 360)?:event.artworkUrl, pictureUrl(meta.optString("pictureUrl"), 1920, 1080)?:event.backgroundArtworkUrl)
        }
        out.distinctBy{it.contentId}
    }

    private fun pictureUrl(raw:String?, width:Int, height:Int):String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return F1TvApiClient.BASE + "/image-resizer/image/" + value +
            "?w=" + width + "&h=" + height + "&o=L&q=HI"
    }

    private fun flattenObjects(root:org.json.JSONArray):List<org.json.JSONObject>{
        val out=mutableListOf<org.json.JSONObject>()
        fun walk(value:Any?){
            when(value){
                is org.json.JSONObject->{
                    out+=value
                    val keys=value.keys()
                    while(keys.hasNext()) walk(value.opt(keys.next()))
                }
                is org.json.JSONArray->for(i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(root); return out
    }

    override suspend fun streams(sessionId:String):Result<List<StreamSource>> = runCatching {
        val container=api.contentVideo(sessionId)
        val metadata=container.optJSONObject("metadata")?:return@runCatching emptyList()
        val additional=metadata.optJSONArray("additionalStreams")?:return@runCatching emptyList()

        val world=mutableListOf<StreamSource>()
        val data=mutableListOf<StreamSource>()
        val timing=mutableListOf<StreamSource>()
        val tracker=mutableListOf<StreamSource>()
        val helicam=mutableListOf<StreamSource>()
        val onboard=mutableListOf<StreamSource>()
        val other=mutableListOf<StreamSource>()

        for(i in 0 until additional.length()){
            val s=additional.optJSONObject(i)?:continue
            val channel=s.optString("channelId").takeIf{it.isNotBlank()}?:continue
            val identifier=s.optString("identifier").uppercase()
            val type=s.optString("type").lowercase()
            val title=s.optString("title").ifBlank{s.optString("reportingName")}
            val driver=listOf(s.optString("driverFirstName"),s.optString("driverLastName"))
                .filter(String::isNotBlank).joinToString(" ").ifBlank{null}

            val kind=when {
                identifier=="DATA" || type.contains("data") -> StreamKind.DATA
                identifier=="TIMING" || type.contains("timing") -> StreamKind.TIMING
                identifier.contains("TRACK") || type.contains("tracker") -> StreamKind.TRACK
                identifier=="HELICAM" || type.contains("helicam") || type.contains("helicopter") -> StreamKind.HELICAM
                identifier=="OBC" || type.contains("onboard") -> StreamKind.ONBOARD
                identifier.contains("WORLD") || type.contains("world") || title.contains("F1 LIVE",true) || title.contains("WORLD",true) -> StreamKind.WORLD
                else -> StreamKind.WORLD
            }

            val source=StreamSource(
                id=when(kind){
                    StreamKind.ONBOARD -> "onboard-"+channel
                    else -> kind.name.lowercase()+"-"+sessionId+"-"+channel
                },
                title=title.ifBlank{
                    when(kind){
                        StreamKind.DATA -> "Data"
                        StreamKind.TIMING -> "Timing"
                        StreamKind.TRACK -> "Driver Tracker"
                        StreamKind.HELICAM -> "Helicam"
                        StreamKind.ONBOARD -> "Onboard "+(driver ?: channel)
                        else -> "F1 Live"
                    }
                },
                kind=kind,
                driver=driver,
                contentId=sessionId,
                channelId=channel
            )

            when(kind){
                StreamKind.WORLD -> world += source
                StreamKind.DATA -> data += source
                StreamKind.TIMING -> timing += source
                StreamKind.TRACK -> tracker += source
                StreamKind.HELICAM -> helicam += source
                StreamKind.ONBOARD -> onboard += source
                else -> other += source
            }
        }

        (world + data + timing + tracker + helicam + other + onboard).distinctBy { it.id }
    }
    override suspend fun resolve(request:PlaybackRequest):Result<PlaybackSession> = runCatching {
        val tv=(context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)==Configuration.UI_MODE_TYPE_TELEVISION
        // Prefer the native big-screen DASH profile on TV, then fall back through
        // the same authorized F1 TV profiles used by the reference implementation.
        val platforms = if (tv) {
            listOf("BIG_SCREEN_DASH","WEB_DASH","BIG_SCREEN_HLS","WEB_HLS","MOBILE_DASH","MOBILE_HLS")
        } else {
            listOf("WEB_DASH","BIG_SCREEN_DASH","WEB_HLS","BIG_SCREEN_HLS","MOBILE_DASH","MOBILE_HLS")
        }
        var last:Throwable?=null
        for((index,platform) in platforms.withIndex()){try{
            val result=api.contentPlay(request.contentId,request.channelId,platform);var playToken=result.playToken;var manifestLicense:String?=null
            if(result.manifestUrl.contains(".mpd",true)){
                val probe=api.prepareManifest(result.manifestUrl)
                if(!probe.successful){
                    throw F1TvException("F1 TV playback manifest unavailable on " + platform)
                }
                playToken=probe.playToken?:playToken
                manifestLicense=probe.licenseUrl
            }
            val license=result.licenseUrl?:manifestLicense?:if(result.manifestUrl.contains(".mpd",true))api.fallbackLicense(request.contentId,request.channelId,platform,result.pipelineVersion,result.streamType)else null
            if(result.manifestUrl.contains(".mpd",true)&&license==null)throw F1TvException("Protected DASH manifest has no Widevine license endpoint")
            val streamHeaders=buildMap{put("Origin",F1TvApiClient.BASE);put("Referer",F1TvApiClient.BASE+"/");put("User-Agent",F1TvApiClient.BROWSER_UA);put("x-f1-device-info",api.deviceInfo());api.authHeaders()["ascendontoken"]?.let{put("ascendontoken",it)};(result.entitlementToken?:api.authHeaders()["entitlementtoken"])?.let{put("entitlementtoken",it)};playToken?.let{put("Cookie","playToken="+it)}}
            val licenseHeaders=buildMap{putAll(streamHeaders);result.drmToken?.let{put("drmtoken",it);put("Authorization","Bearer "+it)}}
            return@runCatching PlaybackSession(result.manifestUrl,if(result.manifestUrl.contains(".m3u8",true))"application/x-mpegURL" else "application/dash+xml",license,licenseHeaders,streamHeaders,request.contentId.startsWith("live-"),request.contentId,request.channelId,result.playApiVersion,result.platform,result.streamType,api.authHeaders()["ascendontoken"],result.entitlementToken?:api.authHeaders()["entitlementtoken"],result.drmType)
        }catch(t:Throwable){last=t;if(index<platforms.lastIndex)delay(450)}}
        throw last?:F1TvException("No F1 TV playback profile succeeded")
    }
    private fun mapSessionType(subtype:String,videoType:String,title:String):String{val s=subtype+" "+videoType+" "+title.lowercase();return when{"sprint" in s->"sprint";"qualifying" in s||"quali" in s->"qualifying";"practice" in s||"fp1" in s||"fp2" in s||"fp3" in s->"practice";"race" in s->"race";else->"other"}}
    private fun normalizeSeries(raw:String):String=when(raw.uppercase().trim()){"FORMULA 1",""->"F1";"FORMULA 2"->"F2";"FORMULA 3"->"F3";"F1 ACADEMY"->"F1 Academy";"PORSCHE"->"Porsche Supercup";else->raw}
}
