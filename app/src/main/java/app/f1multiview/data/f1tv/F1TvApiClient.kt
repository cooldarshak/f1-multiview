package app.f1multiview.data.f1tv

import android.util.Base64
import android.webkit.CookieManager
import app.f1multiview.core.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    }
    private val http=HttpClient.shared
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
        // F1 TV's channel metadata is still exposed through the 3.0 WEB_HLS
        // endpoint used by the reference Android TV client. Keep our newer
        // 4.0 WEB_DASH endpoint as a fallback for accounts/content that use it.
        // Current F1 TV clients use the 4.0 WEB_DASH content endpoint.
        // Keep the older 3.0 WEB_HLS path only as a fallback.
        val endpoints = listOf(
            BASE+"/4.0/R/"+LANG+"/WEB_DASH/ALL/CONTENT/VIDEO/"+contentId+"/"+entitlement+"/"+groupId,
            BASE+"/3.0/R/"+LANG+"/WEB_HLS/ALL/CONTENT/VIDEO/"+contentId+"/"+entitlement+"/"+groupId
        )
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
                last=t
            }
        }
        throw last ?: F1TvException("CONTENT/VIDEO did not return a container")
    }
    suspend fun contentPlay(contentId:String,channelId:String?,platform:String):PlaybackResponse{
        val query="?contentId="+java.net.URLEncoder.encode(contentId,"UTF-8")+(if(channelId.isNullOrBlank())"" else "&channelId="+java.net.URLEncoder.encode(channelId,"UTF-8"))
        // The current reference client uses 2.0 CONTENT/PLAY.
        // Keep 3.0 as a fallback for older content/pipelines.
        val apiVersions=listOf("2.0","3.0")
        var last:Throwable?=null
        for(apiVersion in apiVersions){
            try{
                val response=execute(BASE+"/"+apiVersion+"/R/"+LANG+"/"+platform+"/ALL/CONTENT/PLAY"+query,"GET",null,playHeaders())
                ensureSuccess(response,"content playback")
                return parsePlaybackResponse(response,contentId,channelId,platform)
            }catch(t:Throwable){last=t}
        }
        throw last?:F1TvException("F1 TV playback failed")
    }
    private fun playHeaders(): Map<String,String> = buildMap {
        putAll(authHeaders());put("Origin",BASE);put("Referer",BASE+"/");put("x-f1-device-info",deviceInfo())
    }
    private fun parsePlaybackResponse(response:HttpResponse,contentId:String,channelId:String?,requestedPlatform:String):PlaybackResponse{
        val result=JSONObject(response.body).optJSONObject("resultObj")?:JSONObject(response.body)
        val manifest=firstString(result,"url","manifestUrl","manifestURL","playUrl")?:throw F1TvException("CONTENT/PLAY did not return a manifest URL")
        val license=firstString(result,"laURL","laUrl","licenseUrl","licenseURL")
        val drmToken=firstString(result,"drmToken");val playEntitlement=firstString(result,"entitlementToken")
        val streamType=firstString(result,"streamType");val pipelineVersion=result.optInt("pipelineVersion",-1).takeIf{it>=0}
        val playToken=extractPlayToken(manifest);val playApiVersion=firstString(result,"playApiVersion","playAPIVersion")
        val platform=firstString(result,"platform")?:requestedPlatform;val drmType=firstString(result,"drmType")
        return PlaybackResponse(manifest,license?:fallbackLicense(contentId,channelId,platform,pipelineVersion,streamType),drmToken,playEntitlement,playToken,streamType,pipelineVersion,playApiVersion,platform,drmType)
    }
    suspend fun fetchPage(pageId:Int):org.json.JSONArray{
        val response=execute(BASE+"/2.0/R/"+LANG+"/WEB_DASH/ALL/PAGE/"+pageId+"/"+entitlement+"/"+groupId,"GET",null,authHeaders());ensureSuccess(response,"archive page "+pageId)
        return JSONObject(response.body).optJSONObject("resultObj")?.optJSONArray("containers")?:org.json.JSONArray()
    }
    suspend fun prepareManifest(manifestUrl:String):ManifestProbe=withContext(Dispatchers.IO){
        val builder=Request.Builder().url(manifestUrl).get().header("User-Agent",BROWSER_UA).header("Origin",BASE).header("Referer",BASE+"/").header("Accept","application/dash+xml, application/xml, */*").apply{authHeaders().forEach{(k,v)->header(k,v)}}
            extractPlayToken(manifestUrl)?.takeIf { it.length >= 4 }?.let { builder.header("Cookie","playToken=" + it) }
            val request=builder.build()
        http.newCall(request).execute().use{response->
            val body=response.body?.string().orEmpty()
            val cookie=response.headers.values("Set-Cookie").firstNotNullOfOrNull{c->c.substringBefore(';').takeIf{it.startsWith("playToken=",true)}?.substringAfter('=')}
            ManifestProbe(response.isSuccessful,cookie,extractLicenseUrl(body))
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
data class PlaybackResponse(val manifestUrl:String,val licenseUrl:String?,val drmToken:String?,val entitlementToken:String?,val playToken:String?,val streamType:String?,val pipelineVersion:Int?=null,val playApiVersion:String?=null,val platform:String?=null,val drmType:String?=null)
data class HttpResponse(val code:Int,val isSuccessful:Boolean,val body:String)
class F1TvException(message:String):Exception(message)
