package app.f1multiview.media

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import app.f1multiview.model.StreamSource
import androidx.media3.common.C

class MultiviewSurfaceManager(private val context: Context) {
    data class SurfaceBinding(
        val feedId: String,
        val generation: Long,
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
    private val nextGeneration = java.util.concurrent.atomic.AtomicLong(1L)

    companion object {
        private val BINDING_GENERATION_TAG = View.generateViewId()
    }

    /**
     * Compose owns the root FrameLayout. This manager owns only the rendering child inside it.
     * The root is passed in from AndroidView.update; this manager never clears the root.
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
            container.setTag(BINDING_GENERATION_TAG, current.generation)
            bindings[feedId] = updated
            attachExisting(updated, player, source)
            AppLogger.d("Surface", "bind reused feed=$feedId container=${System.identityHashCode(container)}")
            return
        }

        current?.let {
            releaseBinding(it, unbindCoordinator = false)
            bindings.remove(feedId)
        }
        val generation = nextGeneration.getAndIncrement()
        // Each concrete AndroidView container keeps its own generation. A stale release
        // from an old container cannot match the replacement binding for this feed.
        container.setTag(BINDING_GENERATION_TAG, generation)
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
                feedId = feedId, generation = generation, source = source, protectedContent = protectedContent,
                container = container, owner = player, onTap = onTap,
                textureView = texture, touchInterceptor = touchInterceptor
            )
        } else {
            val surface = SurfaceView(context)
            if (protectedContent) surface.setSecure(true)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                surface.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            }
            installSurfaceDiagnostics(feedId, surface, protectedContent)
            container.addView(surface, params)
            val touchInterceptor = createTouchInterceptor(onTap)
            container.addView(touchInterceptor, params)
            player.setVideoSurfaceView(surface)
            HdrSurfaceHints.apply(surface, source)
            if (player.videoFormat?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) {
                HdrSurfaceHints.applyHlg(surface, source)
            }
            bindings[feedId] = SurfaceBinding(
                feedId = feedId, source = source, protectedContent = protectedContent,
                container = container, owner = player, onTap = onTap,
                surfaceView = surface, touchInterceptor = touchInterceptor
            )
            AppLogger.i(
                "SecureSurface",
                "BOUND feed=$feedId player=${player.id} protected=$protectedContent secureFlagRequested=$protectedContent " +
                    "viewId=${System.identityHashCode(surface)} containerId=${System.identityHashCode(container)} " +
                    "bounds=${surface.width}x${surface.height} api=${android.os.Build.VERSION.SDK_INT}"
            )
        }
    }

    private fun installSurfaceDiagnostics(feedId: String, view: SurfaceView, protectedContent: Boolean) {
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                AppLogger.i(
                    "SecureSurface",
                    "CREATED feed=$feedId protected=$protectedContent viewId=${System.identityHashCode(view)} " +
                        "surfaceValid=${holder.surface.isValid} attached=${view.isAttachedToWindow} " +
                        "bounds=${view.width}x${view.height}"
                )
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                AppLogger.i(
                    "SecureSurface",
                    "CHANGED feed=$feedId protected=$protectedContent viewId=${System.identityHashCode(view)} " +
                        "surfaceValid=${holder.surface.isValid} format=$format size=${width}x$height " +
                        "attached=${view.isAttachedToWindow}"
                )
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                AppLogger.i(
                    "SecureSurface",
                    "DESTROYED feed=$feedId protected=$protectedContent viewId=${System.identityHashCode(view)} " +
                        "attached=${view.isAttachedToWindow} bounds=${view.width}x${view.height}"
                )
            }
        })
    }

    fun update(feedId: String, player: EnginePlayerHandle, source: String) {
        AppLogger.d("Surface", "update feed=$feedId player=${player.id} bound=${bindings.containsKey(feedId)}")
        bindings[feedId]?.let {
            renderCoordinator.update(feedId, source)
            if (it.owner !== player) {
                releaseVideoOutput(it)
                if (it.surfaceView != null) player.setVideoSurfaceView(it.surfaceView)
                if (it.textureView != null) player.setVideoTextureView(it.textureView)
                val updated = it.copy(owner = player, source = source)
                bindings[feedId] = updated
                attachExisting(updated, player, source)
            } else {
                val updated = it.copy(source = source)
                bindings[feedId] = updated
                attachExisting(updated, player, source)
            }
        }
    }

    /** A stale Compose onRelease must not detach a newer binding for the same logical feed. */
    fun detach(feedId: String, player: EnginePlayerHandle, container: FrameLayout) {
        AppLogger.d("Surface", "detach request feed=$feedId player=${player.id} container=${System.identityHashCode(container)}")
        val binding = bindings[feedId] ?: run {
            AppLogger.w("Surface", "detach ignored: no binding feed=$feedId")
            return
        }
        val releasedGeneration = container.getTag(BINDING_GENERATION_TAG) as? Long
        val currentIdentity = SurfaceBindingLease.Identity(
            generation = binding.generation,
            container = binding.container,
            owner = binding.owner
        )
        if (!SurfaceBindingLease.matches(currentIdentity, releasedGeneration, container, player)) {
            AppLogger.w(
                "Surface",
                "detach stale binding ignored feed=$feedId releasedGeneration=$releasedGeneration " +
                    "currentGeneration=${binding.generation} currentContainer=${System.identityHashCode(binding.container)} " +
                    "releasedContainer=${System.identityHashCode(container)} currentOwner=${binding.owner.id}"
            )
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

    fun secureSurfaceDiagnostics(): Map<String, String> {
        val surfaces = bindings.values.mapNotNull { binding ->
            binding.surfaceView?.let { view ->
                binding.feedId to mapOf(
                    "protected" to binding.protectedContent.toString(),
                    "viewId" to System.identityHashCode(view).toString(),
                    "containerId" to System.identityHashCode(binding.container).toString(),
                    "attached" to view.isAttachedToWindow.toString(),
                    "surfaceValid" to view.holder.surface.isValid.toString(),
                    "width" to view.width.toString(),
                    "height" to view.height.toString()
                )
            }
        }
        val protected = surfaces.filter { it.second["protected"] == "true" }
        return mapOf(
            "surfaceBindingCount" to surfaces.size.toString(),
            "protectedSurfaceBindingCount" to protected.size.toString(),
            "attachedProtectedSurfaceCount" to protected.count { it.second["attached"] == "true" }.toString(),
            "validProtectedSurfaceCount" to protected.count { it.second["surfaceValid"] == "true" }.toString(),
            "protectedSurfaces" to protected.joinToString(";") { (id, data) ->
                "$id:view=${data["viewId"]}:container=${data["containerId"]}:attached=${data["attached"]}:" +
                    "valid=${data["surfaceValid"]}:size=${data["width"]}x${data["height"]}"
            }
        )
    }

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

    /** Remove only exact children owned by this binding; never remove unrelated Compose children. */
    private fun releaseBinding(binding: SurfaceBinding, unbindCoordinator: Boolean) {
        releaseVideoOutput(binding)
        binding.surfaceView?.let { view -> if (view.parent === binding.container) binding.container.removeView(view) }
        binding.textureView?.let { view -> if (view.parent === binding.container) binding.container.removeView(view) }
        binding.touchInterceptor?.let { view -> if (view.parent === binding.container) binding.container.removeView(view) }
        if (unbindCoordinator) renderCoordinator.unbind(binding.feedId)
    }

    private fun createTouchInterceptor(onTap: (() -> Unit)?): View =
        View(context).apply {
            isClickable = true
            isFocusable = false
            setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) onTap?.invoke()
                true
            }
        }

    private fun attachExisting(binding: SurfaceBinding, player: EnginePlayerHandle, source: String) {
        binding.touchInterceptor?.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) binding.onTap?.invoke()
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
