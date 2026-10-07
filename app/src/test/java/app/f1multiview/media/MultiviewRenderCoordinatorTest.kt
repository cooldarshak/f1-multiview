package app.f1multiview.media

import app.f1multiview.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewRenderCoordinatorTest {
    private fun feed(id: String, drm: String? = null) =
        StreamSource(
            id = id,
            title = id,
            kind = app.f1multiview.model.StreamKind.ONBOARD,
            url = "https://example.test/$id.m3u8",
            drmLicenseUrl = drm
        )

    @Test
    fun protectedContentAlwaysUsesSecureSurface() {
        val coordinator = MultiviewRenderCoordinator()
        assertEquals(
            MultiviewRenderCoordinator.RenderPath.SURFACE_VIEW,
            coordinator.pathFor(feed("protected", "https://license.test"), screenshotMode = true)
        )
        assertFalse(coordinator.bind(feed("protected", "https://license.test"), "test", true).let {
            coordinator.isGpuComposable(it.feedId)
        })
    }

    @Test
    fun unprotectedScreenshotModeCanUseGpuTexture() {
        val coordinator = MultiviewRenderCoordinator()
        val slot = coordinator.bind(feed("preview"), "screenshot", screenshotMode = true)
        assertEquals(MultiviewRenderCoordinator.RenderPath.GPU_TEXTURE, slot.path)
        assertTrue(coordinator.isGpuComposable("preview"))
    }

    @Test
    fun normalPlaybackUsesSurfacePathEvenWithoutDrm() {
        val coordinator = MultiviewRenderCoordinator()
        val slot = coordinator.bind(feed("normal"), "multiview", screenshotMode = false)
        assertEquals(MultiviewRenderCoordinator.RenderPath.SECURE_SURFACE, slot.path)
    }

    @Test
    fun updateAndUnbindKeepLogicalRenderStateConsistent() {
        val coordinator = MultiviewRenderCoordinator()
        coordinator.bind(feed("a"), "old", false)
        assertEquals("old", coordinator.slot("a")?.source)

        coordinator.update("a", "new")
        assertEquals("new", coordinator.slot("a")?.source)

        coordinator.unbind("a")
        assertNull(coordinator.slot("a"))
        assertFalse(coordinator.isGpuComposable("a"))
    }
}
