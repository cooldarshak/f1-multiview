package app.f1multiview.data.f1tv

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import app.f1multiview.core.auth.SessionStore
import app.f1multiview.core.playback.*
import app.f1multiview.model.*
import app.f1multiview.media.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class AuthorizedF1TvGateway(private val context: Context) : PlaybackGateway {
    private val api=F1TvApiClient()
    private val store=SessionStore(context)
    private val playbackResolveMutex = Mutex()
    private var lastPlaybackResolveAt = 0L
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

    override suspend fun liveTimingHeaders(): Map<String, String> = buildMap {
        api.authHeaders()["ascendontoken"]?.let { put("Authorization", "Bearer " + it) }
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
        for(i in 0 until items.length()){val item=items.optJSONObject(i)?:continue;val meta=item.optJSONObject("metadata")?:continue;val id=meta.optString("contentId").takeIf{it.isNotBlank()}?:continue;val title=meta.optString("title").ifBlank{meta.optJSONObject("emfAttributes")?.optString("Global_Title")?:"Live"};out+=Session(id,title,"LIVE","Live now",true,artworkUrl=pictureUrl(firstArtworkValue(item, meta), 640, 360), backgroundArtworkUrl=pictureUrl(firstArtworkValue(item, meta), 1920, 1080))}
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
    override suspend fun vodEvents(season: VodSeason): Result<List<VodEvent>> = runCatching {
        val root = api.fetchPage(season.pageId)
        val now = System.currentTimeMillis()
        val byPage = linkedMapOf<Int, VodEvent>()

        for (node in F1CatalogParser.flatten(root)) {
            val pageId = F1CatalogParser.pageId(node) ?: continue
            val meta = node.optJSONObject("metadata") ?: org.json.JSONObject()
            val title = F1CatalogParser.title(node, meta)
            if (title.isBlank()) continue

            val upper = title.uppercase()
            val emf = meta.optJSONObject("emfAttributes") ?: org.json.JSONObject()
            val series = F1CatalogParser.series(node, meta)
            val looksLikeMeeting =
                upper.contains("GRAND PRIX") ||
                upper.contains("FORMULA 1") ||
                emf.optString("VideoType").equals("meetings", true) ||
                meta.optString("contentSubtype").equals("MEETING", true) ||
                meta.optString("contentType").contains("meeting", true)

            if (!looksLikeMeeting) continue

            val date = F1CatalogParser.eventDate(node, meta)
            if (date > now) continue

            // The season landing page can contain historical meetings from other years.
            // Only keep meetings that belong to the season currently selected in the UI.
            val titleYear = Regex("\\b(19\\d{2}|20\\d{2})\\b")
                .find(title)
                ?.value
                ?.toIntOrNull()
            val dateYear = if (date > 0L) {
                runCatching {
                    java.time.Instant.ofEpochMilli(date)
                        .atZone(java.time.ZoneId.systemDefault())
                        .year
                }.getOrNull()
            } else {
                null
            }
            val eventYear = dateYear ?: titleYear
            if (eventYear != null && eventYear != season.year) continue

            val props = node.optJSONArray("properties")?.optJSONObject(0) ?: org.json.JSONObject()
            val number = props.optInt("meeting_Number", emf.optInt("Meeting_Number", 0))
            val test = upper.contains("TEST") || upper.contains("PRE-SEASON") || upper.contains("PRESEASON")

            val event = VodEvent(
                pageId = pageId,
                meetingName = title,
                meetingNumber = number,
                seasonYear = season.year,
                isTest = test,
                artworkUrl = pictureUrl(firstArtworkValue(node, meta), 640, 360),
                backgroundArtworkUrl = pictureUrl(firstArtworkValue(node, meta), 1920, 1080),
                series = series,
                startTime = date
            )

            val existing = byPage[pageId]
            if (existing == null || (existing.artworkUrl == null && event.artworkUrl != null)) {
                byPage[pageId] = event
            }
        }

        byPage.values.sortedWith(
            compareBy<VodEvent> { it.meetingNumber == 0 }
                .thenBy { it.meetingNumber }
                .thenBy { it.startTime }
                .thenBy { it.meetingName }
        )
    }

    override suspend fun vodSessions(event: VodEvent): Result<List<VodSession>> = runCatching {
        val root = api.fetchPage(event.pageId)
        val byContent = linkedMapOf<String, VodSession>()

        for (node in F1CatalogParser.flatten(root)) {
            val meta = node.optJSONObject("metadata") ?: continue
            val emf = meta.optJSONObject("emfAttributes") ?: org.json.JSONObject()
            val contentId = meta.optString("contentId").takeIf { it.isNotBlank() } ?: continue
            val title = F1CatalogParser.title(node, meta)
            if (title.isBlank()) continue

            // Some F1 TV event pages contain historical sessions for the same venue.
            // Do not leak those sessions into the selected season.
            val titleYear = Regex("\\b(19\\d{2}|20\\d{2})\\b")
                .find(title)
                ?.value
                ?.toIntOrNull()
            if (titleYear != null && titleYear != event.seasonYear) continue

            val info = F1CatalogParser.sessionInfo(node) ?: continue
            val series = if (info.series == "F1") event.series else info.series
            val videoType = emf.optString("VideoType")
            val isMeetingSession = videoType.equals("meetingSession", true) ||
                videoType.equals("session", true) ||
                meta.optString("contentType").contains("session", true)

            if (!isMeetingSession && info.stage == "other") continue

            val session = VodSession(
                contentId = contentId,
                title = title,
                type = info.stage,
                series = series,
                eventPageId = event.pageId,
                artworkUrl = pictureUrl(firstArtworkValue(node, meta), 640, 360) ?: event.artworkUrl,
                backgroundArtworkUrl = pictureUrl(firstArtworkValue(node, meta), 1920, 1080) ?: event.backgroundArtworkUrl,
                stage = info.stage,
                broadcastVariant = info.broadcastVariant,
                startTime = info.startTime
            )

            val existing = byContent[contentId]
            if (existing == null || (existing.stage == "other" && session.stage != "other")) {
                byContent[contentId] = session
            }
        }

        byContent.values.sortedWith(
            compareBy<VodSession> { stageOrder(it.stage) }
                .thenBy { variantOrder(it.broadcastVariant) }
                .thenBy { it.startTime == 0L }
                .thenBy { it.startTime }
                .thenBy { it.title }
        )
    }

    private fun stageOrder(stage: String): Int = when (stage.lowercase()) {
        "pre-show" -> 5
        "practice-1" -> 10
        "practice-2" -> 20
        "practice-3" -> 30
        "practice" -> 35
        "sprint-qualifying" -> 40
        "sprint" -> 50
        "qualifying" -> 60
        "race" -> 70
        "race-in-30" -> 75
        "post-show" -> 80
        "highlights" -> 85
        "press-conference" -> 90
        "conference" -> 95
        "f1-kids" -> 100
        else -> 110
    }

    private fun variantOrder(variant: String): Int = when (variant.lowercase()) {
        "pre-show" -> 0
        "main" -> 10
        "post-show" -> 20
        "f1-kids" -> 30
        else -> 40
    }

    private fun firstArtworkValue(node: org.json.JSONObject, meta: org.json.JSONObject): String? {
        val emf = meta.optJSONObject("emfAttributes")
        return listOf(
            meta.optString("pictureUrl"),
            node.optString("pictureUrl"),
            meta.optString("imageUrl"),
            node.optString("imageUrl"),
            emf?.optString("pictureUrl")
        ).firstOrNull { !it.isNullOrBlank() }
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

    override suspend fun showsAndDocs(): Result<List<EditorialItem>> = runCatching {
        val out = linkedMapOf<String, EditorialItem>()
        for (pageId in listOf(410, 413)) {
            for (node in F1CatalogParser.flatten(api.fetchPage(pageId))) {
                val meta = node.optJSONObject("metadata") ?: continue
                val contentId = meta.optString("contentId").takeIf { it.isNotBlank() } ?: continue
                val title = F1CatalogParser.title(node, meta).takeIf { it.isNotBlank() } ?: continue
                out.putIfAbsent(
                    contentId,
                    EditorialItem(
                        contentId = contentId,
                        title = title,
                        artworkUrl = pictureUrl(firstArtworkValue(node, meta), 640, 360),
                        pageId = pageId
                    )
                )
            }
        }
        out.values.toList()
    }

    override suspend fun streams(sessionId:String):Result<List<StreamSource>> = runCatching {
        val container=api.contentVideo(sessionId)
        val metadata=container.optJSONObject("metadata")?:return@runCatching emptyList()
        val additional=metadata.optJSONArray("additionalStreams")

        // Editorial VOD (press conferences, shows, highlights, Race in 30, etc.)
        // often has no additionalStreams because it is a single playable asset.
        // Treat the asset itself as the World/main source instead of returning
        // an empty stream list and trying to resolve a multiview feed.
        if (additional == null || additional.length() == 0) {
            return@runCatching listOf(
                StreamSource(
                    id = "vod-" + sessionId,
                    title = metadata.optString("title").ifBlank { "F1 TV" },
                    kind = StreamKind.WORLD,
                    contentId = sessionId,
                    channelId = null,
                    isLive = false
                )
            )
        }

        val world=mutableListOf<StreamSource>()
        val data=mutableListOf<StreamSource>()
        val timing=mutableListOf<StreamSource>()
        val tracker=mutableListOf<StreamSource>()
        val helicam=mutableListOf<StreamSource>()
        val onboard=mutableListOf<StreamSource>()
        val other=mutableListOf<StreamSource>()
        val f1Dash=mutableListOf<StreamSource>()

        for(i in 0 until additional.length()){
            val s=additional.optJSONObject(i)?:continue
            // F1 TV returns the real per-feed contentId/channelId in playbackUrl.
            // Using the parent session id for every feed can make VOD channels
            // appear in the UI but fail to resolve/play.
            val playbackUrl=s.optString("playbackUrl").trim()
            val playbackUri=runCatching { Uri.parse(
                if (playbackUrl.startsWith("http", true)) playbackUrl else F1TvApiClient.BASE + playbackUrl
            ) }.getOrNull()
            val channel=s.optString("channelId").takeIf{it.isNotBlank()}
                ?: playbackUri?.getQueryParameter("channelId")
                ?: continue
            val feedContentId=playbackUri?.getQueryParameter("contentId")
                ?.takeIf{it.isNotBlank()}
                ?: sessionId
            val identifier=s.optString("identifier").uppercase()
            val type=s.optString("type").lowercase()
            val title=s.optString("title").ifBlank{s.optString("reportingName")}
            val driver=listOf(s.optString("driverFirstName"),s.optString("driverLastName"))
                .filter(String::isNotBlank).joinToString(" ").ifBlank{null}

            val titleUpper=title.uppercase()
            val kind=when {
                identifier.contains("F1_DASH") || identifier.contains("F1DASH") ||
                    titleUpper.contains("F1 DASH") -> StreamKind.F1_DASH
                identifier=="TRACKER" || type.contains("tracker") ||
                    titleUpper.contains("DRIVER TRACKER") || titleUpper=="TRACKER" -> StreamKind.TRACK
                identifier=="DATA" || type.contains("data") || titleUpper=="DATA" -> StreamKind.DATA
                identifier=="TIMING" || type.contains("timing") || type.contains("telemetry") ||
                    titleUpper.contains("LIVE TIMING") || titleUpper.contains("TIMING") ||
                    titleUpper.contains("TELEMETRY") -> StreamKind.TIMING
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
                        StreamKind.TRACK -> "Tracker"
                        StreamKind.F1_DASH -> "F1 Dash Data"
                        StreamKind.HELICAM -> "Helicam"
                        StreamKind.ONBOARD -> "Onboard "+(driver ?: channel)
                        else -> "F1 Live"
                    }
                },
                kind=kind,
                driver=driver,
                contentId=feedContentId,
                channelId=channel
            )

            when(kind){
                StreamKind.WORLD -> world += source
                StreamKind.DATA -> data += source
                StreamKind.TIMING -> timing += source
                StreamKind.TRACK -> tracker += source
                StreamKind.F1_DASH -> f1Dash += source
                StreamKind.HELICAM -> helicam += source
                StreamKind.ONBOARD -> onboard += source
                else -> other += source
            }
        }

        // Every item parsed from F1 additionalStreams remains an official F1
        // playable feed, including TRACKER, TIMING and F1_DASH when F1 supplies them.
        // Our rendered data views are separate entries and must never replace an
        // official F1 stream.
        val f1DashData = StreamSource(
            id = "f1-dash-data-" + sessionId,
            title = "F1 Dash Data",
            kind = StreamKind.F1_DASH_DATA,
            contentId = null,
            channelId = null
        )
        val trackMap = StreamSource(
            id = "track-map-" + sessionId,
            title = "Driver Tracker Map",
            kind = StreamKind.TRACK_MAP,
            contentId = null,
            channelId = null
        )
        (world + tracker + data + f1Dash + timing + helicam + listOf(f1DashData, trackMap) + other + onboard)
            .distinctBy { it.id }
    }
    override suspend fun resolve(request:PlaybackRequest):Result<PlaybackSession> = playbackResolveMutex.withLock {
        val waitMs = 450L - (System.currentTimeMillis() - lastPlaybackResolveAt)
        if (waitMs > 0L) delay(waitMs)
        lastPlaybackResolveAt = System.currentTimeMillis()
        runCatching {
        ensurePlaybackSession()
        val tv=(context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)==Configuration.UI_MODE_TYPE_TELEVISION
        // Prefer the native big-screen DASH profile on TV, then fall back through
        // the same authorized F1 TV profiles used by the reference implementation.
        val platforms = if (tv) {
            listOf("BIG_SCREEN_DASH","WEB_DASH","BIG_SCREEN_HLS","WEB_HLS","MOBILE_DASH","MOBILE_HLS")
        } else {
            listOf("WEB_DASH","BIG_SCREEN_DASH","WEB_HLS","BIG_SCREEN_HLS","MOBILE_DASH","MOBILE_HLS")
        }
        var last: Throwable? = null
        for ((index, platform) in platforms.withIndex()) {
            try {
                val result = api.contentPlay(request.contentId, request.channelId, platform)
                AppLogger.i(
                    "F1Playback",
                    "CONTENT_PLAY platform=$platform requestedApiVersion=${result.requestedApiVersion ?: "unknown"} " +
                        "httpStatus=${result.httpStatus ?: -1} playApiVersion=${result.playApiVersion ?: "absent"} " +
                        "manifest=${if (result.manifestUrl.contains(".mpd", true)) "dash" else "hls"}"
                )
                val explicitWidevine = result.drmType?.contains("widevine", true) == true ||
                    result.streamType?.contains("DASHWV", true) == true ||
                    result.streamType?.contains("WIDEVINE", true) == true
                val explicitlyNonWidevineDashSingle = result.manifestUrl.contains(".mpd", true) &&
                    result.streamType?.contains("DASH_SINGLE", true) == true && !explicitWidevine
                if (explicitlyNonWidevineDashSingle) {
                    throw F1TvException("F1 TV profile explicitly declares DASH_SINGLE without Widevine metadata")
                }
                var playToken = result.playToken
                var manifestLicense: String? = null
                if (result.manifestUrl.contains(".mpd", true)) {
                    val probe = api.prepareManifest(result.manifestUrl)
                    if (!probe.successful) throw F1TvException("F1 TV playback manifest unavailable on $platform")
                    playToken = probe.playToken ?: playToken
                    manifestLicense = probe.licenseUrl
                }
                val license = result.licenseUrl ?: manifestLicense ?: if (result.manifestUrl.contains(".mpd", true)) {
                    api.fallbackLicense(request.contentId, request.channelId, platform, result.pipelineVersion, result.streamType, result.drmType)
                } else null
                if (result.manifestUrl.contains(".mpd", true) && license == null) {
                    throw F1TvException("Protected DASH manifest has no Widevine license endpoint")
                }
                val streamHeaders = buildMap<String, String> {
                    put("Origin", F1TvApiClient.BASE)
                    put("Referer", F1TvApiClient.BASE + "/")
                    put("User-Agent", F1TvApiClient.BROWSER_UA)
                    put("x-f1-device-info", api.deviceInfo())
                    api.authHeaders()["ascendontoken"]?.let { put("ascendontoken", it) }
                    (result.entitlementToken ?: api.authHeaders()["entitlementtoken"])?.let { put("entitlementtoken", it) }
                    playToken?.let { put("Cookie", "playToken=$it") }
                }
                val licenseHeaders = buildMap<String, String> {
                    putAll(streamHeaders)
                    result.drmToken?.let {
                        put("drmtoken", it)
                        put("Authorization", "Bearer $it")
                    }
                }
                return@runCatching PlaybackSession(
                    result.manifestUrl,
                    if (result.manifestUrl.contains(".m3u8", true)) "application/x-mpegURL" else "application/dash+xml",
                    license,
                    licenseHeaders,
                    streamHeaders,
                    request.contentId.startsWith("live-"),
                    request.contentId,
                    request.channelId,
                    result.playApiVersion,
                    result.platform,
                    result.streamType,
                    api.authHeaders()["ascendontoken"],
                    result.entitlementToken ?: api.authHeaders()["entitlementtoken"],
                    result.drmType,
                    playToken,
                    drmProtected = !license.isNullOrBlank() ||
                        result.drmType?.contains("widevine", true) == true ||
                        result.streamType?.contains("DASHWV", true) == true
                )
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                last = failure
                val reasonCode = when {
                    failure.message?.contains("DASH_SINGLE without Widevine", ignoreCase = true) == true -> "PROFILE_NOT_WIDEVINE"
                    failure.message?.contains("no Widevine license endpoint", ignoreCase = true) == true -> "NO_WIDEVINE_LICENSE_ENDPOINT"
                    failure.message?.contains("manifest unavailable", ignoreCase = true) == true -> "MANIFEST_UNAVAILABLE"
                    failure.message?.contains("manifest URL", ignoreCase = true) == true -> "MANIFEST_URL_MISSING"
                    else -> failure.javaClass.simpleName
                }
                AppLogger.w(
                    "F1Playback",
                    "AUTHORIZED_PROFILE_REJECTED contentId=${request.contentId} platform=$platform reason=$reasonCode; trying next authorized profile"
                )
                if (index < platforms.lastIndex) delay(450L)
            }
        }
        throw last ?: F1TvException("No authorized F1 TV playback profile succeeded")
        }
    }
    private suspend fun ensurePlaybackSession() {
        val saved = store.get() ?: return
        if (api.isTokenExpired(saved, 5 * 60L) || !api.isAuthenticated()) {
            val fresh = if (api.isTokenExpired(saved, 5 * 60L)) refreshFromBrowserSession() else saved
            if (fresh.isNullOrBlank()) throw F1TvException("F1 TV session expired. Please sign in again.")
            api.initialize(fresh)
            store.put(fresh)
        }
    }

    private fun mapSessionType(subtype:String,videoType:String,title:String):String{val s=subtype+" "+videoType+" "+title.lowercase();return when{"sprint" in s->"sprint";"qualifying" in s||"quali" in s->"qualifying";"practice" in s||"fp1" in s||"fp2" in s||"fp3" in s->"practice";"race" in s->"race";else->"other"}}
    private fun normalizeSeries(raw:String):String=when(raw.uppercase().trim()){"FORMULA 1",""->"F1";"FORMULA 2"->"F2";"FORMULA 3"->"F3";"F1 ACADEMY"->"F1 Academy";"PORSCHE"->"Porsche Supercup";else->raw}
}
