package app.f1multiview.media

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import app.f1multiview.model.StreamSource

/**
 * Centralized rendering-surface lifecycle.
 *
 * The engine owns the mapping between logical feed IDs and Android rendering views.
 * Compose receives a host container and never needs to know which surface is attached
 * to the decoder. Protected F1 playback remains on SurfaceView; TextureView is only
 * permitted for the existing debug screenshot path.
 */
class SurfaceManager(
    private val context: Context
) {
    data class SurfaceBinding(
        val feedId: String,
        val source: String,
        val protectedContent: Boolean,
        val container: FrameLayout,
        val surfaceView: SurfaceView? = null,
        val textureView: TextureView? = null
    )

    private val bindings = linkedMapOf<String, SurfaceBinding>()

    fun attach(
        feedId: String,
        player: EnginePlayerHandle,
        stream: StreamSource,
        source: String,
        screenshotMode: Boolean = false
    ): FrameLayout {
        val existing = bindings[feedId]
        if (existing != null) {
            attachExisting(existing, player, source)
            return existing.container
        }

        val container = FrameLayout(context)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val protectedContent = stream.drmLicenseUrl != null
        val useTexture = screenshotMode && !protectedContent

        if (useTexture) {
            val texture = TextureView(context)
            container.addView(texture, params)
            player.setVideoTextureView(texture)
            bindings[feedId] = SurfaceBinding(
                feedId, source, protectedContent, container, textureView = texture
            )
        } else {
            val surface = SurfaceView(context)
            container.addView(surface, params)
            player.setVideoSurfaceView(surface)
            HdrSurfaceHints.apply(surface, source)
            if (player.videoFormat?.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_HLG) {
                HdrSurfaceHints.applyHlg(surface, source)
            }
            bindings[feedId] = SurfaceBinding(
                feedId, source, protectedContent, container, surfaceView = surface
            )
        }

        return container
    }

    fun update(feedId: String, player: EnginePlayerHandle, source: String) {
        bindings[feedId]?.let { attachExisting(it, player, source) }
    }

    fun detach(feedId: String, player: EnginePlayerHandle) {
        val binding = bindings.remove(feedId) ?: return
        binding.surfaceView?.let { player.clearVideoSurfaceView(it) }
        binding.textureView?.let { player.clearVideoTextureView(it) }
        binding.container.removeAllViews()
    }

    fun clear() {
        bindings.clear()
    }

    fun binding(feedId: String): SurfaceBinding? = bindings[feedId]

    private fun attachExisting(binding: SurfaceBinding, player: EnginePlayerHandle, source: String) {
        binding.textureView?.let { player.setVideoTextureView(it) }
        binding.surfaceView?.let {
            player.setVideoSurfaceView(it)
            HdrSurfaceHints.apply(it, source)
            if (player.videoFormat?.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_HLG) {
                HdrSurfaceHints.applyHlg(it, source)
            }
        }
    }
}
