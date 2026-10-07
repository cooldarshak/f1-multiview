package app.f1multiview.media

import app.f1multiview.model.StreamSource

/**
 * Rendering policy for multiview.
 *
 * Widevine-protected F1 video must remain on a secure SurfaceView path.
 * TextureView is reserved for explicitly requested unprotected/screenshot
 * rendering where a GPU-compositable surface is useful.
 */
class MultiviewRenderCoordinator {
    enum class RenderPath {
        SURFACE_VIEW,
        GPU_TEXTURE
    }

    data class RenderSlot(
        val feedId: String,
        val path: RenderPath,
        val protectedContent: Boolean,
        val source: String,
        val zOrder: Int
    )

    private val slots = linkedMapOf<String, RenderSlot>()

    fun pathFor(stream: StreamSource, screenshotMode: Boolean): RenderPath =
        if (stream.drmLicenseUrl != null || !screenshotMode) {
            RenderPath.SURFACE_VIEW
        } else {
            RenderPath.GPU_TEXTURE
        }

    fun bind(
        stream: StreamSource,
        source: String,
        screenshotMode: Boolean,
        zOrder: Int = 0
    ): RenderSlot {
        val path = pathFor(stream, screenshotMode)
        return RenderSlot(
            feedId = stream.id,
            path = path,
            protectedContent = stream.drmLicenseUrl != null,
            source = source,
            zOrder = zOrder
        ).also { slots[stream.id] = it }
    }

    fun update(feedId: String, source: String): RenderSlot? =
        slots[feedId]?.copy(source = source).also { if (it != null) slots[feedId] = it }

    fun unbind(feedId: String) {
        slots.remove(feedId)
    }

    fun slot(feedId: String): RenderSlot? = slots[feedId]

    fun slots(): List<RenderSlot> = slots.values.toList()

    fun clear() = slots.clear()

    fun isGpuComposable(feedId: String): Boolean =
        slots[feedId]?.path == RenderPath.GPU_TEXTURE
}
