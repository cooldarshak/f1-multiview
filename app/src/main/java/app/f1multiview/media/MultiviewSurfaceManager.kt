package app.f1multiview.media

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import app.f1multiview.model.StreamSource
import androidx.media3.common.C

class MultiviewSurfaceManager(private val context: Context) {
    data class SurfaceBinding(
        val feedId: String,
        val source: String,
        val protectedContent: Boolean,
        val container: FrameLayout,
        val owner: EnginePlayerHandle,
        val surfaceView: SurfaceView? = null,
        val textureView: TextureView? = null
    )

    private val bindings = linkedMapOf<String, SurfaceBinding>()
    private val renderCoordinator = MultiviewRenderCoordinator()

    /**
     * AndroidView owns the root container. The surface manager must therefore never hand
     * Compose a cached/previously parented container. A new attachment always gets a new
     * FrameLayout; the previous binding is detached from Media3 first.
     */
    fun attach(feedId: String, player: EnginePlayerHandle, stream: StreamSource, source: String, screenshotMode: Boolean = false): FrameLayout {
        bindings.remove(feedId)?.let { releaseBinding(it, unbindCoordinator = false) }

        val container = FrameLayout(context)
        val params = FrameLayout.LayoutParams(-1, -1)
        val renderSlot = renderCoordinator.bind(stream, source, screenshotMode)
        val protectedContent = renderSlot.protectedContent

        if (renderSlot.path == MultiviewRenderCoordinator.RenderPath.GPU_TEXTURE) {
            val texture = TextureView(context)
            container.addView(texture, params)
            player.setVideoTextureView(texture)
            bindings[feedId] = SurfaceBinding(
                feedId = feedId,
                source = source,
                protectedContent = protectedContent,
                container = container,
                owner = player,
                textureView = texture
            )
        } else {
            val surface = SurfaceView(context)
            if (protectedContent) {
                // Keep Widevine output on a secure surface.
                surface.setSecure(true)
            }
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // In scrolling feed rails, keep the surface alive with the view attachment
                // rather than visibility so the next frame can start without a surface-creation wait.
                surface.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            }
            container.addView(surface, params)
            player.setVideoSurfaceView(surface)
            HdrSurfaceHints.apply(surface, source)
            if (player.videoFormat?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) {
                HdrSurfaceHints.applyHlg(surface, source)
            }
            bindings[feedId] = SurfaceBinding(
                feedId = feedId,
                source = source,
                protectedContent = protectedContent,
                container = container,
                owner = player,
                surfaceView = surface
            )
        }
        return container
    }

    fun update(feedId: String, player: EnginePlayerHandle, source: String) {
        bindings[feedId]?.let {
            renderCoordinator.update(feedId, source)
            if (it.owner !== player) {
                releaseVideoOutput(it)
                if (it.surfaceView != null) player.setVideoSurfaceView(it.surfaceView)
                if (it.textureView != null) player.setVideoTextureView(it.textureView)
                bindings[feedId] = it.copy(owner = player, source = source)
            } else {
                attachExisting(it, player, source)
            }
        }
    }

    /**
     * Releases only the binding represented by the AndroidView being removed. A stale
     * Compose onRelease must not detach a newer binding for the same logical feed.
     */
    fun detach(feedId: String, player: EnginePlayerHandle, container: FrameLayout) {
        val binding = bindings[feedId] ?: return
        if (binding.container !== container || binding.owner !== player) return
        bindings.remove(feedId)
        renderCoordinator.unbind(feedId)
        releaseBinding(binding, unbindCoordinator = false)
    }

    fun binding(feedId: String): SurfaceBinding? = bindings[feedId]

    fun boundFeedIds(): Set<String> = bindings.keys.toSet()

    fun renderSlot(feedId: String): MultiviewRenderCoordinator.RenderSlot? = renderCoordinator.slot(feedId)

    fun renderSlots(): List<MultiviewRenderCoordinator.RenderSlot> = renderCoordinator.slots()

    fun isGpuComposable(feedId: String): Boolean = renderCoordinator.isGpuComposable(feedId)

    fun clear() {
        bindings.values.toList().forEach { releaseBinding(it, unbindCoordinator = false) }
        bindings.clear()
        renderCoordinator.clear()
    }

    private fun releaseVideoOutput(binding: SurfaceBinding) {
        binding.surfaceView?.let(binding.owner::clearVideoSurfaceView)
        binding.textureView?.let(binding.owner::clearVideoTextureView)
    }

    private fun releaseBinding(binding: SurfaceBinding, unbindCoordinator: Boolean) {
        releaseVideoOutput(binding)
        binding.container.removeAllViews()
        if (unbindCoordinator) {
            renderCoordinator.unbind(binding.feedId)
        }
    }

    private fun attachExisting(binding: SurfaceBinding, player: EnginePlayerHandle, source: String) {
        binding.textureView?.let(player::setVideoTextureView)
        binding.surfaceView?.let {
            player.setVideoSurfaceView(it)
            HdrSurfaceHints.apply(it, source)
            if (player.videoFormat?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) {
                HdrSurfaceHints.applyHlg(it, source)
            }
        }
    }
}
