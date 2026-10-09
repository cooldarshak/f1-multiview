package app.f1multiview.media

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
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
        val textureView: TextureView? = null,
        val touchInterceptor: View? = null
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
        val desiredProtected = renderCoordinator.isProtected(stream)
        if (current != null && current.container === container && current.owner === player &&
            current.protectedContent == desiredProtected) {
            renderCoordinator.update(feedId, source)
            val updated = current.copy(onTap = onTap, source = source)
            bindings[feedId] = updated
            attachExisting(updated, player, source)
            AppLogger.d("Surface", "bind reused feed=$feedId container=${System.identityHashCode(container)}")
            return
        }

        current?.let {
            // Clear the old player's output before replacing a surface whose secure
            // classification changed. Keep the logical coordinator slot during upgrade.
            releaseBinding(it, unbindCoordinator = false)
            bindings.remove(feedId)
        }
        val params = FrameLayout.LayoutParams(-1, -1)
        val renderSlot = renderCoordinator.bind(stream, source, screenshotMode)
        val protectedContent = renderSlot.protectedContent

        AppLogger.d("Surface", "bind create feed=$feedId path=${renderSlot.path} protected=$protectedContent")
        if (renderSlot.path == MultiviewRenderCoordinator.RenderPath.GPU_TEXTURE) {
            val texture = TextureView(context)
            container.addView(texture, params)
            player.setVideoTextureView(texture)
            val touchInterceptor = createTouchInterceptor(onTap)
            container.addView(touchInterceptor, params)
            bindings[feedId] = SurfaceBinding(
                feedId = feedId,
                source = source,
                protectedContent = protectedContent,
                container = container,
                owner = player,
                onTap = onTap,
                textureView = texture,
                touchInterceptor = touchInterceptor
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
            val touchInterceptor = createTouchInterceptor(onTap)
            container.addView(touchInterceptor, params)
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
                surfaceView = surface,
                touchInterceptor = touchInterceptor
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
                bindings[feedId] = it.copy(owner = player, source = source)
                attachExisting(it.copy(owner = player), player, source)
            } else {
                bindings[feedId] = it.copy(source = source)
                attachExisting(it.copy(source = source), player, source)
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

    /**
     * Remove only views owned by this binding. The container is supplied by Compose and
     * may contain children owned by another layer; removeAllViews() can silently destroy
     * newer/replacement content during a stale rebind or release callback.
     */
    private fun releaseBinding(binding: SurfaceBinding, unbindCoordinator: Boolean) {
        releaseVideoOutput(binding)
        binding.surfaceView?.let { view ->
            if (view.parent === binding.container) binding.container.removeView(view)
        }
        binding.textureView?.let { view ->
            if (view.parent === binding.container) binding.container.removeView(view)
        }
        binding.touchInterceptor?.let { view ->
            if (view.parent === binding.container) binding.container.removeView(view)
        }
        if (unbindCoordinator) {
            renderCoordinator.unbind(binding.feedId)
        }
    }

    private fun createTouchInterceptor(onTap: (() -> Unit)?): View =
        View(context).apply {
            isClickable = true
            isFocusable = false
            setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    onTap?.invoke()
                }
                true
            }
        }

    private fun attachExisting(binding: SurfaceBinding, player: EnginePlayerHandle, source: String) {
        binding.touchInterceptor?.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                binding.onTap?.invoke()
            }
            true
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
