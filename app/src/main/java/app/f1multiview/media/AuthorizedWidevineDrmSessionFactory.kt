package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import app.f1multiview.model.StreamSource

/**
 * Creates the app's authorized Widevine session-manager configuration from F1 playback metadata.
 *
 * Each playback consumer must create and own its own manager instance. Do not share one manager
 * between an ExoPlayer and an app-owned SampleQueue/MediaCodec pipeline: each consumer has its own
 * looper, acquire/release lifecycle, and secure-decoder/output responsibilities.
 *
 * This factory preserves the existing F1 license endpoint and request-header precedence. It does
 * not bypass licensing, downgrade Widevine security level, or authorize protected output.
 */
@OptIn(UnstableApi::class)
internal object AuthorizedWidevineDrmSessionFactory {
    internal data class Configuration(
        val licenseUrl: String,
        val requestHeaders: Map<String, String>,
        val userAgent: String
    ) {
        fun createSessionManager(): DefaultDrmSessionManager {
            val dataSource = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(requestHeaders)
            val callback = HttpMediaDrmCallback(licenseUrl, /* forceDefaultLicenseUrl= */ true, dataSource)
            requestHeaders.forEach { (name, value) -> callback.setKeyRequestProperty(name, value) }
            return DefaultDrmSessionManager.Builder()
                .setMultiSession(false)
                .build(callback)
        }
    }

    fun configurationFor(stream: StreamSource): Configuration? {
        val licenseUrl = stream.drmLicenseUrl?.takeIf(String::isNotBlank) ?: return null
        val headers = LinkedHashMap(stream.drmRequestHeaders.ifEmpty { stream.requestHeaders })
        stream.playToken?.takeIf(String::isNotBlank)?.let { token ->
            // Do not replace a session Cookie when adding the playback token. License endpoints
            // may require both the pre-existing session cookie and the playToken cookie value.
            val cookieKey = headers.keys.firstOrNull { it.equals("Cookie", ignoreCase = true) } ?: "Cookie"
            val existingCookie = headers[cookieKey].orEmpty()
            val cookieParts = existingCookie.split(';').map(String::trim).filter(String::isNotEmpty)
            val tokenCookie = "playToken=$token"
            val mergedCookies = cookieParts.filterNot {
                it.substringBefore('=').trim().equals("playToken", ignoreCase = true)
            } + tokenCookie
            headers[cookieKey] = mergedCookies.joinToString("; ")
        }
        return Configuration(
            licenseUrl = licenseUrl,
            requestHeaders = headers.toMap(),
            userAgent = stream.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                ?: "Mozilla/5.0"
        )
    }

    fun createSessionManager(stream: StreamSource): DefaultDrmSessionManager? =
        configurationFor(stream)?.createSessionManager()
}
