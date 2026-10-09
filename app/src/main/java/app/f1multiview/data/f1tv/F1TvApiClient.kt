package app.f1multiview.data.f1tv

import android.util.Base64
import android.webkit.CookieManager
import app.f1multiview.core.network.HttpClient
import app.f1multiview.media.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class F1TvApiClient {
    companion object {
        const val BASE="https://f1tv.formula1.com"
        const val AUTH="https://api.formula1.com/v2/account/subscriber/authenticate/by-password"
        val BROWSER_UA:String get()="Mozilla/5.0 (Linux; Android "+android.os.Build.VERSION.RELEASE+"; "+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+") AppleWebKit/537.36 Chrome/140.0 Safari/537.36"
        private const val LANG="ENG"
        private const val DEFAULT_ENTITLEMENT="F1_TV_Pro_Annual"
        private const val DEFAULT_GROUP="2"
        private val JSON="application/json; charset=utf-8".toMediaType()
        private val API_VERSION_PATTERN = Regex("^\\d+(?:\\.\\d+)?$")
        private const val API_CONFIG_CACHE_MS = 30_000L

        /**
         * Put the server-configured API version first, then preserve known legacy fallbacks.
         * Validate the version before using it in a URL path.
         */
        internal fun apiVersionCandidates(configuredVersion:String?,fallbackVersions:List<String>):List<String>{
            val preferred=configuredVersion?.trim()?.takeIf{API_VERSION_PATTERN.matches(it)}
            return (listOfNotNull(preferred)+fallbackVersions).distinct()
        }

        private fun normalizeApiVersion(value:String?):String? =
            value?.trim()?.takeIf{API_VERSION_PATTERN.matches(it)}
    }
    private val http=HttpClient.shared
    private val apiConfigMutex=Mutex()
    @Volatile private var apiConfigFetchedAt=0L
    @Volatile private var configuredPlayApiVersion:String?=null
    @Volatile private var configuredVideoApiVersion:String?=null

    /**
     * F1's public /config endpoint is the source of truth for playAPIVersion and
     * videoAPIVersion. Cache it for the server's configured 30-second interval.
     * If it is unavailable, retain the legacy fallback order rather than blocking
     * authorized playback.
     */
    private suspend fun ensureApiConfig() = apiConfigMutex.withLock {
        val now=System.currentTimeMillis()
        if(apiConfigFetchedAt>0L && now-apiConfigFetchedAt<API_CONFIG_CACHE_MS) return@withLock
        var cacheAttempt = true
        try {
            val configHeaders=mapOf(
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to BROWSER_UA,
                "Origin" to BASE,
                "Referer" to BASE+"/"
            )
            val response=execute("$BASE/config","GET",null,configHeaders)
            if(!response.isSuccessful){
                AppLogger.w("F1Playback","F1_API_CONFIG_UNAVAILABLE httpStatus=${response.code}")
            } else {
                val config=runCatching{JSONObject(response.body).optJSONObject("apiConfig")}.getOrNull()
                val play=normalizeApiVersion(config?.optString("playAPIVersion"))
                val video=normalizeApiVersion(config?.optString("videoAPIVersion"))
                configuredPlayApiVersion=play
                configuredVideoApiVersion=video
                AppLogger.i("F1Playback","F1_API_CONFIG httpStatus=${response.code} playApiVersion=${play ?: "absent"} videoApiVersion=${video ?: "absent"}")
            }
        } catch(t:Throwable) {
            if(t is CancellationException) {
                cacheAttempt = false
                throw t
            }
            AppLogger.w("F1Playback","F1_API_CONFIG_UNAVAILABLE reason=${t.javaClass.simpleName}")
        } finally {
            if(cacheAttempt) apiConfigFetchedAt=System.currentTimeMillis()
        }
    }
    @Volatile private var subscriptionToken:String?=null
    @Volatile private var entitlementToken:String?=null
    @Volatile private var entitlement=DEFAULT_ENTITLEMENT
    @Volatile private var groupId=DEFAULT_GROUP
    fun isAuthenticated()=!subscriptionToken.isNullOrBlank()&&!entitlementToken.isNullOrBlank()
    fun deviceInfo(): String = "device=android_tv;screen=bigscreen;os=android;model="+android.os.Build.MODEL.replace(";","_")+";osVersion="+android.os.Build.VERSION.SDK_INT+";manufacturer="+android.os.Build.MANUFACTURER.replace(";","_")+";appVersion=1.0;playerVersion=Media3;tms=1;"
    fun isTokenExpired(token: String, skewSeconds: Long = 60): Boolean {
        return runCatching {
            val parts = token.split('.')
            if (parts.size < 2) return@runCatching false
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            val exp = JSONObject(payload).optLong("exp", 0L)
            exp > 0L && exp * 1000L <= System.currentTimeMillis() + skewSeconds * 1000L
        }.getOrDefault(false)
    }

    fun subscriptionTokenFromCookieHeader(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        val raw = cookieHeader.split(';').asSequence().map { it.trim() }
            .firstOrNull { it.startsWith("login-session=", ignoreCase = true) }
            ?.substringAfter('=') ?: return null
        return runCatching {
            val decoded = java.net.URLDecoder.decode(raw, "UTF-8")
            val json = JSONObject(decoded)
            json.optJSONObject("data")?.optString("subscriptionToken")
                ?.takeIf { it.length >= 50 }
                ?: json.optString("subscriptionToken").takeIf { it.length >= 50 }
        }.getOrNull()
    }

    fun subscriptionTokenFromWebViewCookies(): String? {
        val cm = CookieManager.getInstance()
        return listOf(
            "https://account.formula1.com/",
            "https://formula1.com/",
            "https://f1tv.formula1.com/"
        ).asSequence()
            .mapNotNull { url -> subscriptionTokenFromCookieHeader(cm.getCookie(url)) }
            .firstOrNull { !isTokenExpired(it) }
    }

    suspend fun login(email:String,password:String){
        val response=execute(AUTH,"POST",JSONObject().put("Login",email).put("Password",password).toString(),emptyMap())
        if(response.code==403)throw F1TvException("F1 TV rejected direct login (HTTP 403). Use browser sign-in.")
        if(!response.isSuccessful)throw F1TvException("F1 TV login failed: HTTP "+response.code)
        val json=JSONObject(response.body)
        val token=firstString(json,"subscriptionToken","token","accessToken","access_token")?:json.optJSONObject("resultObj")?.let{firstString(it,"subscriptionToken","token")}
        require(!token.isNullOrBlank()){"F1 TV login response did not contain a subscription token"}
        initialize(token!!)
    }
    suspend fun initialize(token:String){require(token.length>=50){"Invalid F1 TV session token"};subscriptionToken=token;refreshEntitlement();refreshLocation()}
    suspend fun refreshEntitlement(){
        val response=execute(BASE+"/2.0/R/"+LANG+"/WEB_DASH/ALL/USER/ENTITLEMENT","GET",null,authHeaders());ensureSuccess(response,"entitlement")
        val result=JSONObject(response.body).optJSONObject("resultObj")?:throw F1TvException("F1 TV entitlement response missing resultObj")
        entitlementToken=firstString(result,"entitlementToken")?:throw F1TvException("F1 TV entitlement token missing")
    }
    suspend fun refreshLocation(){
        val response=execute(BASE+"/1.0/R/"+LANG+"/WEB_DASH/ALL/USER/LOCATION","GET",null,authHeaders());ensureSuccess(response,"location")
        val loc=JSONObject(response.body).optJSONObject("resultObj")?.optJSONArray("userLocation")?.optJSONObject(0)
        if(loc!=null){entitlement=loc.optString("entitlement").ifBlank{entitlement};groupId=loc.optString("groupId").ifBlank{groupId}}
    }
    suspend fun liveNow():JSONObject{
        val response=execute(BASE+"/1.0/R/"+LANG+"/WEB_DASH/ALL/EVENTS/LIVENOW/"+entitlement+"/"+groupId,"GET",null,authHeaders());ensureSuccess(response,"live catalog");return JSONObject(response.body)
    }
    suspend fun contentVideo(contentId:String):JSONObject{
        // F1 publishes the active video API version in /config. Keep the
        // known 4.0 then 3.0 fallback order if that config is unavailable.
        ensureApiConfig()
        val endpoints = apiVersionCandidates(configuredVideoApiVersion,listOf("4.0","3.0")).map { apiVersion ->
            val platform = if(apiVersion=="3.0") "WEB_HLS" else "WEB_DASH"
            BASE+"/"+apiVersion+"/R/"+LANG+"/"+platform+"/ALL/CONTENT/VIDEO/"+contentId+"/"+entitlement+"/"+groupId
        }
        var last:Throwable? = null
        for (endpoint in endpoints) {
            try {
                val response=execute(endpoint,"GET",null,authHeaders())
                ensureSuccess(response,"content video")
                val container=JSONObject(response.body).optJSONObject("resultObj")
                    ?.optJSONArray("containers")?.optJSONObject(0)
                if (container != null) return container
                last=F1TvException("CONTENT/VIDEO returned no container")
            } catch (t:Throwable) {
                if(t is CancellationException) throw t
                last=t
            }
        }
        throw last ?: F1TvException("CONTENT/VIDEO did not return a container")
    }
    suspend fun contentPlay(contentId:String,channelId:String?,platform:String):PlaybackResponse{
        val query="?contentId="+java.net.URLEncoder.encode(contentId,"UTF-8")+(if(channelId.isNullOrBlank())"" else "&channelId="+java.net.URLEncoder.encode(channelId,"UTF-8"))
        // Use F1's configured playAPIVersion first. The live /config currently
        // advertises 3.0 while videoAPIVersion is 4.0; hardcoding 2.0 first
        // causes any valid 2.0 manifest response to mask the configured playback API.
        ensureApiConfig()
        val apiVersions=apiVersionCandidates(configuredPlayApiVersion,listOf("2.0","3.0"))
        var last:Throwable?=null
        for(apiVersion in apiVersions){
            try{
                val response=execute(BASE+"/"+apiVersion+"/R/"+LANG+"/"+platform+"/ALL/CONTENT/PLAY"+query,"GET",null,playHeaders())
                logPlaybackResponseShape(apiVersion,platform,response)
                ensureSuccess(response,"content playback")
                return parsePlaybackResponse(response,contentId,channelId,platform).copy(
                    requestedApiVersion=apiVersion,
                    httpStatus=response.code
                )
            }catch(t:Throwable){
                if(t is CancellationException) throw t
                last=t
                AppLogger.w("F1Playback","CONTENT_PLAY_VERSION_FAILED platform=$platform requestedApiVersion=$apiVersion reason=${t.javaClass.simpleName}")
            }
        }
        throw last?:F1TvException("F1 TV playback failed")
    }

    /** Logs response shape only; never logs response values, credentials, URLs, or body. */
    private fun logPlaybackResponseShape(apiVersion:String,platform:String,response:HttpResponse){
        val root=runCatching{JSONObject(response.body)}.getOrNull()
        val result=root?.optJSONObject("resultObj")
        val topKeys=root?.let{jsonKeys(it)}?:emptyList()
        val resultKeys=result?.let{jsonKeys(it)}?:emptyList()
        // F1's response wraps resultObj.settings one level below the fields
        // inspected by the old diagnostics. Walk the complete JSON tree so nested
        // TME flags/payloads are visible without logging any response values.
        val objects=root?.let(::jsonObjectsDepthFirst).orEmpty()
        val relevant=objects.flatMap{(path,obj)->
            jsonKeys(obj).filter{key->
                key.contains("tme",true) || key.contains("tiled",true) ||
                    key.contains("multichannel",true) || key.contains("channelViewMode",true) ||
                    key.contains("playApiVersion",true)
            }.map{key->"$path.$key:${jsonType(obj.opt(key))}"}
        }.distinct().sorted()
        val availability=objects.flatMap{(path,obj)->
            jsonKeys(obj).filter{it.equals("isTmeAvailable",true)}.map{key->
                val value=obj.opt(key)
                val safeValue=if(value is Boolean) value.toString() else "not_boolean"
                "$path.$key:${jsonType(value)}:$safeValue"
            }
        }.ifEmpty{listOf("absent")}
        AppLogger.i("F1Playback",
            "CONTENT_PLAY_RESPONSE_SHAPE requestedApiVersion=$apiVersion platform=$platform httpStatus=${response.code} " +
                "json=${if(root==null) "invalid_or_non_object" else "object"} topLevelKeys=$topKeys resultObjKeys=$resultKeys " +
                "tmeRelatedFields=$relevant isTmeAvailable=$availability"
        )
    }

    private fun jsonKeys(obj:JSONObject):List<String>{
        val keys=obj.keys()
        val out=mutableListOf<String>()
        while(keys.hasNext()) out+=keys.next()
        return out.sorted()
    }

    private fun jsonObjectsDepthFirst(root:JSONObject):List<Pair<String,JSONObject>>{
        val out=mutableListOf<Pair<String,JSONObject>>()
        fun visit(value:Any?,path:String){
            when(value){
                is JSONObject -> {
                    out += path to value
                    val keys=value.keys()
                    while(keys.hasNext()){
                        val key=keys.next()
                        val childPath=if(path=="\$") key else "$path.$key"
                        visit(value.opt(key),childPath)
                    }
                }
                is org.json.JSONArray -> for(index in 0 until value.length()){
                    visit(value.opt(index),"$path[$index]")
                }
            }
        }
        visit(root,"\$")
        return out
    }

    private fun jsonType(value:Any?):String=when(value){
        null,JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is org.json.JSONArray -> "array"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        else -> value.javaClass.simpleName
    }
    private fun playHeaders(): Map<String,String> = buildMap {
        putAll(authHeaders());put("Origin",BASE);put("Referer",BASE+"/");put("x-f1-device-info",deviceInfo())
    }
    internal fun parsePlaybackResponse(response:HttpResponse,contentId:String,channelId:String?,requestedPlatform:String):PlaybackResponse{
        val root=JSONObject(response.body)
        // F1's production ContentPlayResponse exposes tmeJson and playback fields
        // directly on the response object. Some older/alternate responses wrap
        // them under resultObj, so keep that as a compatibility fallback.
        val resultObj=root.optJSONObject("resultObj")
        val sources=listOf(root,resultObj).filterNotNull()
        // TME fields are usually on ContentPlayResponse, but API variants can
        // nest them inside resultObj.settings. Search nested objects as well.
        val tmeSources=jsonObjectsDepthFirst(root).map{it.second}
        val tmeElement=tmeSources.asSequence()
            .flatMap { obj -> sequenceOf("tmeJson","tme","TME").mapNotNull { key ->
                if(!obj.has(key)) null else obj.opt(key)
            } }
            .firstOrNull { value ->
                when(value){
                    null, JSONObject.NULL -> false
                    is org.json.JSONObject -> value.length()>0
                    is org.json.JSONArray -> value.length()>0
                    is String -> value.isNotBlank() && !value.equals("null",true)
                    else -> value.toString().isNotBlank() && !value.toString().equals("null",true)
                }
            }
        val tmeJson=tmeElement?.toString()
        val manifest=sources.asSequence().mapNotNull { firstString(it,"url","manifestUrl","manifestURL","playUrl") }.firstOrNull()
        if (manifest.isNullOrBlank() && tmeJson.isNullOrBlank()) throw F1TvException("CONTENT/PLAY returned neither a manifest URL nor TME JSON")
        val license=sources.asSequence().mapNotNull { firstString(it,"laURL","laUrl","licenseUrl","licenseURL") }.firstOrNull()
        val drmToken=sources.asSequence().mapNotNull { firstString(it,"drmToken") }.firstOrNull()
        val playEntitlement=sources.asSequence().mapNotNull { firstString(it,"entitlementToken") }.firstOrNull()
        val streamType=sources.asSequence().mapNotNull { firstString(it,"streamType") }.firstOrNull()
        val pipelineVersion=sources.asSequence().mapNotNull { it.optInt("pipelineVersion",-1).takeIf{v->v>=0} }.firstOrNull()
        val playToken=extractPlayToken(manifest.orEmpty())
        val playApiVersion=sources.asSequence().mapNotNull { firstString(it,"playApiVersion","playAPIVersion") }.firstOrNull()
        val platform=sources.asSequence().mapNotNull { firstString(it,"platform") }.firstOrNull()?:requestedPlatform
        val drmType=sources.asSequence().mapNotNull { firstString(it,"drmType") }.firstOrNull()
        val channelViewMode=tmeSources.asSequence().mapNotNull { firstString(it,"channelViewMode","channelViewModeOverride") }.firstOrNull()
        return PlaybackResponse(manifest.orEmpty(),license?:fallbackLicense(contentId,channelId,platform,pipelineVersion,streamType),drmToken,playEntitlement,playToken,streamType,pipelineVersion,playApiVersion,platform,drmType,tmeJson,channelViewMode)
    }
    suspend fun fetchPage(pageId:Int):org.json.JSONArray{
        val response=execute(BASE+"/2.0/R/"+LANG+"/WEB_DASH/ALL/PAGE/"+pageId+"/"+entitlement+"/"+groupId,"GET",null,authHeaders());ensureSuccess(response,"archive page "+pageId)
        return JSONObject(response.body).optJSONObject("resultObj")?.optJSONArray("containers")?:org.json.JSONArray()
    }
    suspend fun prepareManifest(manifestUrl:String):ManifestProbe=withContext(Dispatchers.IO){
        // F1OpenViewer establishes the manifest playToken with a HEAD request before
        // fetching the playlist. Some F1 CDN variants reject a direct GET until this
        // cookie has been issued.
        val initialToken=extractPlayToken(manifestUrl)?.takeIf { it.length >= 4 }
        val baseHeaders=buildMap<String,String>{
            put("User-Agent",BROWSER_UA)
            put("Origin",BASE)
            put("Referer",BASE+"/")
            put("Accept","application/dash+xml, application/xml, */*")
            authHeaders().forEach{(k,v)->put(k,v)}
        }
        var playToken=initialToken
        var headOk=false
        runCatching {
            val headBuilder=Request.Builder().url(manifestUrl).head()
            baseHeaders.forEach{(k,v)->headBuilder.header(k,v)}
            playToken?.let{headBuilder.header("Cookie","playToken="+it)}
            http.newCall(headBuilder.build()).execute().use{response->
                headOk=response.isSuccessful
                response.headers.values("Set-Cookie").firstNotNullOfOrNull{cookie->
                    cookie.substringBefore(';').takeIf{it.startsWith("playToken=",true)}?.substringAfter('=')
                }?.takeIf{it.length>=4}?.let{playToken=it}
            }
        }
        val builder=Request.Builder().url(manifestUrl).get()
        baseHeaders.forEach{(k,v)->builder.header(k,v)}
        playToken?.let { builder.header("Cookie","playToken="+it) }
        val request=builder.build()
        http.newCall(request).execute().use{response->
            val body=response.body?.string().orEmpty()
            response.headers.values("Set-Cookie").firstNotNullOfOrNull{cookie->
                cookie.substringBefore(';').takeIf{it.startsWith("playToken=",true)}?.substringAfter('=')
            }?.takeIf{it.length>=4}?.let{playToken=it}
            val license=extractLicenseUrl(body)
            ManifestProbe(response.isSuccessful && (headOk || response.isSuccessful),playToken,license)
        }
    }
    data class ManifestProbe(val successful:Boolean,val playToken:String?,val licenseUrl:String?)
    private fun extractLicenseUrl(text:String):String?{
        val patterns=listOf(
            Regex("licenseServerUrl\\s*=\\s*[\"'](https?://[^\"']+)[\"']",RegexOption.IGNORE_CASE),
            Regex("(?:laurl|Laurl|LAURL)\\s*[=:]\\s*[\"'](https?://[^\"']+)[\"']",RegexOption.IGNORE_CASE),
            Regex("https://f1tv\\.formula1\\.com/[^\\s\"'<>]+/CONTENT/LA[^\\s\"'<>]*",RegexOption.IGNORE_CASE)
        )
        for(r in patterns){val m=r.find(text)?:continue;val value=if(m.groupValues.size>1)m.groupValues[1] else m.value;if(value.startsWith("http"))return value.replace("&amp;","&")}
        return null
    }
    fun clear(){subscriptionToken=null;entitlementToken=null}
    fun authHeaders(): Map<String, String> =buildMap{
        put("Accept","application/json, text/plain, */*");put("User-Agent","F1MultiView/1.0 Android")
        subscriptionToken?.let{put("ascendontoken",it)};entitlementToken?.let{put("entitlementtoken",it)}
    }
    private suspend fun execute(url:String,method:String,body:String?,headers:Map<String,String>):HttpResponse=withContext(Dispatchers.IO){
        val builder=Request.Builder().url(url);headers.forEach{(k,v)->builder.header(k,v)}
        if(method=="POST")builder.post((body?:"").toRequestBody(JSON))else builder.get()
        http.newCall(builder.build()).execute().use{r->HttpResponse(r.code,r.isSuccessful,r.body?.string().orEmpty())}
    }
    fun fallbackLicense(contentId:String,channelId:String?,platform:String,pipelineVersion:Int?,streamType:String?):String?{
        val useWidevine=(pipelineVersion?:-1)>=3||(pipelineVersion==null&&streamType?.contains("WV",true)==true);if(!useWidevine)return null
        return BASE+"/2.0/R/"+LANG+"/"+platform+"/ALL/CONTENT/LA/widevine?contentId="+java.net.URLEncoder.encode(contentId,"UTF-8")+(if(channelId.isNullOrBlank())"" else "&channelId="+java.net.URLEncoder.encode(channelId,"UTF-8"))
    }
    private fun extractPlayToken(url:String):String?{
        return runCatching {
            val match=Regex("/pa_([^/?#]+)",RegexOption.IGNORE_CASE).find(url) ?: return@runCatching null
            var encoded=match.groupValues[1].replace('-','+').replace('_','/')
            encoded += "=".repeat((4-encoded.length%4)%4)
            val decoded=String(Base64.decode(encoded,Base64.DEFAULT),Charsets.ISO_8859_1)
            decoded.split('|').mapNotNull { part ->
                val idx=part.indexOf(':')
                if(idx>0) part.substring(0,idx) to part.substring(idx+1) else null
            }.toMap()["token"]?.takeIf { it.length >= 4 && it.none { ch -> ch.code in 0..31 || ch.code == 127 } }
        }.getOrNull()
    }
    private fun ensureSuccess(response:HttpResponse,operation:String){
        if(!response.isSuccessful)throw F1TvException("F1 TV "+operation+" failed: HTTP "+response.code)
        val json=runCatching{JSONObject(response.body)}.getOrNull();val resultCode=json?.optString("resultCode")
        if(!resultCode.isNullOrBlank()&&resultCode!="OK")throw F1TvException(json.optString("message","F1 TV "+operation+" failed"))
    }
    private fun firstString(obj:JSONObject,vararg keys:String):String?=keys.firstNotNullOfOrNull{key->obj.optString(key).takeIf{it.isNotBlank()}}
}
data class PlaybackResponse(val manifestUrl:String,val licenseUrl:String?,val drmToken:String?,val entitlementToken:String?,val playToken:String?,val streamType:String?,val pipelineVersion:Int?=null,val playApiVersion:String?=null,val platform:String?=null,val drmType:String?=null,val tmeJson:String?=null,val channelViewMode:String?=null,val requestedApiVersion:String?=null,val httpStatus:Int?=null)
data class HttpResponse(val code:Int,val isSuccessful:Boolean,val body:String)
class F1TvException(message:String):Exception(message)
