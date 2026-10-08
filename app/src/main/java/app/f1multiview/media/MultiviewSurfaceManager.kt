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
        val onTap: (() -> Unit)? = null,
        val surfaceView: SurfaceView? = null,
        val textureView: TextureView? = null
    )

    private val bindings = linkedMapOf<String, SurfaceBinding>()
    private val renderCoordinator = MultiviewRenderCoordinator()

    /**
     * Compose owns the root FrameLayout. This manager owns only the rendering child inside it.
     *
     * The root is intentionally passed in from AndroidView.update, not populated in
     * AndroidView.factory. AndroidView adds the factory result to its own ViewHolder before
     * update is executed, so the media layer can never return a pre-populated/externally
     * parented root to Compose.
     */
    fun bind(
        feedId: String,
        player: EnginePlayerHandle,
        stream: StreamSource,
        source: String,
        container: FrameLayout,
        screenshotMode: Boolean = false,
        onTap: (() -> Unit)? = null
    ) {
        AppLogger.d("Surface", "bind start feed=$feedId player=${player.id} source=$source container=${System.identityHashCode(container)}")
        val current = bindings[feedId]
        if (current != null && current.container === container && current.owner === player) {
            renderCoordinator.update(feedId, source)
            attachExisting(current, player, source)
            AppLogger.d("Surface", "bind reused feed=$feedId container=${System.identityHashCode(container)}")
            return
        }

        current?.let { releaseBinding(it, unbindCoordinator = false) }
        val params = FrameLayout.LayoutParams(-1, -1)
        val renderSlot = renderCoordinator.bind(stream, source, screenshotMode)
        val protectedContent = renderSlot.protectedContent

        AppLogger.d("Surface", "bind create feed=$feedId path=${renderSlot.path} protected=$protectedContent")
        if (renderSlot.path == MultiviewRenderCoordinator.RenderPath.GPU_TEXTURE) {
            val texture = TextureView(context)
            container.addView(texture, params)
            player.setVideoTextureView(texture)
            texture.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) onTap?.invoke()
                false
            }
            bindings[feedId] = SurfaceBinding(
                feedId = feedId,
                source = source,
                protectedContent = protectedContent,
                container = container,
                owner = player,
                onTap = onTap,
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
            surface.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) onTap?.invoke()
                false
            }
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
                onTap = onTap,
                surfaceView = surface
            )
        }
    }

    fun update(feedId: String, player: EnginePlayerHandle, source: String) {
        AppLogger.d("Surface", "update feed=$feedId player=${player.id} bound=${bindings.containsKey(feedId)}")
        bindings[feedId]?.let {
            renderCoordinator.update(feedId, source)
            if (it.owner !== player) {
                releaseVideoOutput(it)
                if (it.surfaceView != null) player.setVideoSurfaceView(it.surfaceView)
                if (it.textureView != null) player.setVideoTextureView(it.textureView)
                bindings[feedId] = it.copy(owner = player, source = source, onTap = onTap)
                attachExisting(it.copy(owner = player, onTap = onTap), player, source)
            } else {
                bindings[feedId] = it.copy(source = source, onTap = onTap)
                attachExisting(it.copy(source = source, onTap = onTap), player, source)
            }
        }
    }

    /**
     * Releases only the binding represented by the AndroidView being removed. A stale
     * Compose onRelease must not detach a newer binding for the same logical feed.
     */
    fun detach(feedId: String, player: EnginePlayerHandle, container: FrameLayout) {
        AppLogger.d("Surface", "detach request feed=$feedId player=${player.id} container=${System.identityHashCode(container)}")
        val binding = bindings[feedId] ?: run {
            AppLogger.w("Surface", "detach ignored: no binding feed=$feedId")
            return
        }
        if (binding.container !== container || binding.owner !== player) {
            AppLogger.w("Surface", "detach identity mismatch feed=$feedId currentContainer=${System.identityHashCode(binding.container)} currentOwner=${binding.owner.id}")
            return
        }
        bindings.remove(feedId)
        renderCoordinator.unbind(feedId)
        releaseBinding(binding, unbindCoordinator = false)
        AppLogger.d("Surface", "detach complete feed=$feedId")
    }

    fun binding(feedId: String): SurfaceBinding? = bindings[feedId]

    fun boundFeedIds(): Set<String> = bindings.keys.toSet()

    fun renderSlot(feedId: String): MultiviewRenderCoordinator.RenderSlot? = renderCoordinator.slot(feedId)

    fun renderSlots(): List<MultiviewRenderCoordinator.RenderSlot> = renderCoordinator.slots()

    fun isGpuComposable(feedId: String): Boolean = renderCoordinator.isGpuComposable(feedId)

    fun clear() {
        AppLogger.i("Surface", "clear bindings=${bindings.keys}")
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
        binding.textureView?.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) binding.onTap?.invoke()
            false
        }
        binding.surfaceView?.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) binding.onTap?.invoke()
            false
        }
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
