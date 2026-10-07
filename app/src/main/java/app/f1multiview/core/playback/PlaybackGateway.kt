package app.f1multiview.core.playback

import app.f1multiview.model.Session
import app.f1multiview.model.StreamSource

data class ProviderCredentials(val username: String, val password: String)
data class PlaybackRequest(val contentId: String, val channelId: String? = null, val requestedQuality: Quality = Quality.AUTO)
enum class Quality { AUTO, UHD, FHD, HD, SD }
data class PlaybackSession(
    val manifestUrl: String,
    val mimeType: String? = null,
    val licenseUrl: String? = null,
    val licenseHeaders: Map<String, String> = emptyMap(),
    val streamHeaders: Map<String, String> = emptyMap(),
    val isLive: Boolean = true,
    val contentId: String? = null,
    val channelId: String? = null,
    val playApiVersion: String? = null,
    val platform: String? = null,
    val streamType: String? = null,
    val ascendonToken: String? = null,
    val entitlementToken: String? = null,
    val drmType: String? = null,
    val playToken: String? = null,
    /** Raw TME payload returned by F1 CONTENT/PLAY when the content uses Tiledmedia Multiview. */
    val tmeJson: String? = null,
    /** F1/TME channel view mode when supplied by CONTENT/PLAY. */
    val channelViewMode: String? = null
) {
    /**
     * Parsed TME is deliberately exposed as a first-class playback capability.
     *
     * The current Media3 fallback does not pretend to implement Tiledmedia's native
     * single-player decoder. Callers can inspect/configure the tiled session separately
     * and fall back to ordinary stream playback until a native tiled backend is present.
     */
    val tiledMultiview: TiledMultiviewSession?
        get() = tmeJson?.let(TiledMultiviewSessionParser::parse)
}
data class VodSeason(val year: Int, val pageId: Int)
data class VodEvent(
    val pageId: Int,
    val meetingName: String,
    val meetingNumber: Int,
    val seasonYear: Int,
    val isTest: Boolean = false,
    val artworkUrl: String? = null,
    val backgroundArtworkUrl: String? = null,
    val series: String = "F1",
    val startTime: Long = 0L
)
data class EditorialItem(val contentId:String,val title:String,val artworkUrl:String?=null,val pageId:Int=0)
data class VodSession(
    val contentId: String,
    val title: String,
    val type: String,
    val series: String = "F1",
    val eventPageId: Int,
    val artworkUrl: String? = null,
    val backgroundArtworkUrl: String? = null,
    val stage: String = "other",
    val broadcastVariant: String = "main",
    val startTime: Long = 0L
)
interface PlaybackGateway {
    suspend fun signIn(credentials: ProviderCredentials): Result<Unit>
    suspend fun signInWithSessionToken(token: String): Result<Unit>
    suspend fun restoreSession(): Result<Boolean>
    suspend fun signOut()
    suspend fun sessions(): Result<List<Session>>
    suspend fun streams(sessionId: String): Result<List<StreamSource>>
    suspend fun resolve(request: PlaybackRequest): Result<PlaybackSession>
    suspend fun vodSeasons(): Result<List<VodSeason>>
    suspend fun vodEvents(season: VodSeason): Result<List<VodEvent>>
    suspend fun vodSessions(event: VodEvent): Result<List<VodSession>>
    suspend fun showsAndDocs(): Result<List<EditorialItem>>
    /** Bearer credentials for authenticated F1 live GPS/telemetry topics. */
    suspend fun liveTimingHeaders(): Map<String, String> = emptyMap()
}
