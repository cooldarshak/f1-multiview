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
        val touchInterceptor: View? = null,
        /** False while a validated own-engine decoder exclusively owns the protected Surface. */
        val playerOutputAttached: Boolean = true
    )

    private val bindings = linkedMapOf<String, SurfaceBinding>()
    private val renderCoordinator = MultiviewRenderCoordinator()
    private val nextGeneration = java.util.concurrent.atomic.AtomicLong(1L)
    private val containerGenerations = java.util.WeakHashMap<FrameLayout, Long>()

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
        val reuseIdentity = SurfaceBindingLease.ReuseIdentity(container, player, desiredProtected)
        val currentIdentity = current?.let {
            SurfaceBindingLease.ReuseIdentity(it.container, it.owner, it.protectedContent)
        }
        if (SurfaceBindingLease.canReuse(currentIdentity, reuseIdentity)) {
            renderCoordinator.update(feedId, source)
            val sourceChanged = current!!.source != source
            val updated = current.copy(onTap = onTap, source = source)
            containerGenerations[container] = current.generation
            bindings[feedId] = updated
            updateTouchInterceptor(updated)
            // Do not repeatedly call setVideoSurfaceView/setVideoTextureView from AndroidView.update.
            // Reattaching the same output on every Compose recomposition is unnecessary and can
            // disturb Media3's output lifecycle. Source-specific HDR hints are safe to refresh.
            if (sourceChanged) applySurfaceHints(updated, source)
            AppLogger.d("Surface", "bind reused feed=$feedId generation=${current.generation} " +
                "container=${System.identityHashCode(container)} sourceChanged=$sourceChanged outputReattached=false")
            return
        }

        current?.let {
            releaseBinding(it, unbindCoordinator = false)
            bindings.remove(feedId)
            containerGenerations.remove(it.container)
        }
        val generation = nextGeneration.getAndIncrement()
        // Each concrete AndroidView container keeps its own generation. A stale release
        // from an old container cannot match the replacement binding for this feed.
        containerGenerations[container] = generation
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
                feedId = feedId, generation = generation, source = source, protectedContent = protectedContent,
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
                // A Surface wrapper can survive a destroy/create cycle. Advance the generation so
                // an old decoder lease cannot become valid again when the wrapper is reused.
                val current = bindings[feedId]
                if (current?.surfaceView === view) {
                    val generation = nextGeneration.getAndIncrement()
                    containerGenerations[current.container] = generation
                    bindings[feedId] = current.copy(generation = generation)
                    AppLogger.w(
                        "SecureSurface",
                        "LEASE_INVALIDATED feed=$feedId generation=$generation reason=surface-destroyed"
                    )
                }
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
                val generation = nextGeneration.getAndIncrement()
                containerGenerations[it.container] = generation
                val updated = it.copy(
                    owner = player,
                    source = source,
                    generation = generation,
                    playerOutputAttached = true
                )
                bindings[feedId] = updated
                attachExisting(updated, player, source)
            } else {
                val sourceChanged = it.source != source
                val updated = it.copy(source = source)
                bindings[feedId] = updated
                updateTouchInterceptor(updated)
                if (sourceChanged) applySurfaceHints(updated, source)
                AppLogger.d("Surface", "update reused output feed=$feedId generation=${it.generation} " +
                    "sourceChanged=$sourceChanged outputReattached=false")
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
        val releasedGeneration = containerGenerations[container]
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
        containerGenerations.remove(container)
        renderCoordinator.unbind(feedId)
        releaseBinding(binding, unbindCoordinator = false)
        AppLogger.d("Surface", "detach complete feed=$feedId")
    }

    /**
     * Detaches Media3's video output and lends the existing secure SurfaceView surface to the
     * app-owned protected decoder. The returned generation-bound lease becomes invalid on rebind,
     * surface replacement, detach, or cleanup.
     */
    internal fun claimProtectedSurfaceForOwnDecoder(feedId: String): ProtectedSurfaceLease? {
        val binding = bindings[feedId] ?: return null
        if (!binding.protectedContent) {
            AppLogger.e("SecureSurface", "OWN_DECODER_CLAIM_REJECTED feed=$feedId reason=surface-not-protected")
            return null
        }
        val view = binding.surfaceView ?: return null
        val surface = view.holder.surface
        if (!view.isAttachedToWindow || !surface.isValid || view.width <= 0 || view.height <= 0) {
            AppLogger.w(
                "SecureSurface",
                "OWN_DECODER_CLAIM_DEFERRED feed=$feedId attached=${view.isAttachedToWindow} " +
                    "valid=${surface.isValid} size=${view.width}x${view.height}"
            )
            return null
        }
        if (binding.playerOutputAttached) binding.owner.clearVideoSurfaceView(view)
        bindings[feedId] = binding.copy(playerOutputAttached = false)
        AppLogger.i(
            "SecureSurface",
            "OWN_DECODER_CLAIMED feed=$feedId generation=${binding.generation} " +
                "surfaceId=${System.identityHashCode(surface)} secureFlagRequested=true"
        )
        return ProtectedSurfaceLease(
            feedId = feedId,
            generation = binding.generation,
            surface = surface,
            secureFlagRequested = true,
            width = view.width,
            height = view.height
        )
    }

    internal fun isCurrentProtectedSurfaceLease(lease: ProtectedSurfaceLease): Boolean {
        val binding = bindings[lease.feedId]
        val view = binding?.surfaceView
        val currentSurface = view?.holder?.surface
        return ProtectedSurfaceLeasePolicy.isCurrent(
            leaseGeneration = lease.generation,
            currentGeneration = binding?.generation,
            leaseSurface = lease.surface,
            currentSurface = currentSurface,
            secureFlagRequested = lease.secureFlagRequested,
            protectedContent = binding?.protectedContent == true,
            playerOutputAttached = binding?.playerOutputAttached != false,
            viewAttached = view?.isAttachedToWindow == true,
            surfaceValid = lease.surface.isValid && currentSurface?.isValid == true
        )
    }

    /** Restores the existing single-feed Media3 output after the own decoder has been closed. */
    internal fun restorePlayerOutput(lease: ProtectedSurfaceLease): Boolean {
        if (!isCurrentProtectedSurfaceLease(lease)) return false
        val binding = bindings[lease.feedId] ?: return false
        val view = binding.surfaceView ?: return false
        val updated = binding.copy(playerOutputAttached = true)
        bindings[lease.feedId] = updated
        binding.owner.setVideoSurfaceView(view)
        AppLogger.i(
            "SecureSurface",
            "OWN_DECODER_RELEASED feed=${lease.feedId} generation=${lease.generation} playerOutputRestored=true"
        )
        return true
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
                    "playerOutputAttached" to binding.playerOutputAttached.toString(),
                    "ownDecoderClaimed" to (!binding.playerOutputAttached && binding.protectedContent).toString(),
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
        containerGenerations.clear()
        renderCoordinator.clear()
    }

    private fun releaseVideoOutput(binding: SurfaceBinding) {
        if (!binding.playerOutputAttached) return
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

    private fun updateTouchInterceptor(binding: SurfaceBinding) {
        binding.touchInterceptor?.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) binding.onTap?.invoke()
            true
        }
    }

    private fun applySurfaceHints(binding: SurfaceBinding, source: String) {
        binding.surfaceView?.let {
            HdrSurfaceHints.apply(it, source)
            if (binding.owner.videoFormat?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) {
                HdrSurfaceHints.applyHlg(it, source)
            }
        }
    }

    private fun attachExisting(binding: SurfaceBinding, player: EnginePlayerHandle, source: String) {
        updateTouchInterceptor(binding)
        if (!binding.playerOutputAttached) return
        binding.textureView?.let(player::setVideoTextureView)
        binding.surfaceView?.let {
            player.setVideoSurfaceView(it)
            applySurfaceHints(binding, source)
        }
    }
}
