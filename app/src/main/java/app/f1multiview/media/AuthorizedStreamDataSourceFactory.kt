package app.f1multiview.media

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import app.f1multiview.model.StreamSource

/** Shared authorized request-header and media-role source stack for Media3 and app-owned extraction. */
internal object AuthorizedStreamDataSourceFactory {
    fun create(stream: StreamSource): DataSource.Factory {
        val url = requireNotNull(stream.url) { "Authorized stream URL is missing" }
        val base = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(
                stream.requestHeaders["User-Agent"]
                    ?: "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
            )
            .setDefaultRequestProperties(stream.requestHeaders)
        val roleAware = MediaSegmentRoleDataSource.Factory(base)
        return if (url.contains(".m3u8", true)) {
            F1CmafHlsDrmFixingDataSource.Factory(roleAware)
        } else {
            roleAware
        }
    }
}
