package app.f1multiview.media

import android.content.Context
import android.content.ContextWrapper
import app.f1multiview.core.playback.TiledMultiviewFeed
import app.f1multiview.core.playback.TiledMultiviewSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class TestContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
}

class MultiviewPlaybackBackendTest {
    private val session = TiledMultiviewSession(
        version = 1,
        channel = "F1",
        contentId = 123,
        tileWidth = 1920,
        tileHeight = 1080,
        feeds = listOf(
            TiledMultiviewFeed(
                index = 0,
                channelId = 1,
                encoderId = "world",
                url = "https://example.invalid/world",
                uuid = "world-uuid",
                audioEnglish = "World",
                audioSpanish = null,
                subtitleEnglish = null,
                subtitleSpanish = null
            ),
            TiledMultiviewFeed(
                index = 1,
                channelId = 2,
                encoderId = "onboard",
                url = "https://example.invalid/onboard",
                uuid = "onboard-uuid",
                audioEnglish = "Onboard",
                audioSpanish = null,
                subtitleEnglish = null,
                subtitleSpanish = null
            )
        )
    )

    @Test
    fun nativeTmeBackend_isExplicitlyUnavailableWithoutSdk() {
        val backend = NativeTmePlaybackBackend(TestContext())

        assertFalse(backend.status.available)
        assertFalse(backend.status.singlePlayer)
        assertTrue(backend.status.reason.contains("independent F1 feeds"))
        assertFalse(backend.canHandle(session))
    }

    @Test
    fun media3Fallback_isAvailableButNotSinglePlayer() {
        val backend = Media3MultiPlayerFallbackBackend()

        assertTrue(backend.status.available)
        assertFalse(backend.status.singlePlayer)
        assertTrue(backend.canHandle(session))
    }
}
