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
        val surfaceView: SurfaceView? = null,
        val textureView: TextureView? = null
    )

    private val bindings = linkedMapOf<String, SurfaceBinding>()

    fun attach(feedId: String, player: EnginePlayerHandle, stream: StreamSource, source: String, screenshotMode: Boolean = false): FrameLayout {
        bindings[feedId]?.let { existing ->
            attachExisting(existing, player, source)
            return existing.container
        }
        val container = FrameLayout(context)
        val params = FrameLayout.LayoutParams(-1, -1)
        val protectedContent = stream.drmLicenseUrl != null
        if (screenshotMode && !protectedContent) {
            val texture = TextureView(context)
            container.addView(texture, params)
            player.setVideoTextureView(texture)
            bindings[feedId] = SurfaceBinding(feedId, source, protectedContent, container, textureView = texture)
        } else {
            val surface = SurfaceView(context)
            container.addView(surface, params)
            player.setVideoSurfaceView(surface)
            HdrSurfaceHints.apply(surface, source)
            if (player.videoFormat?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) HdrSurfaceHints.applyHlg(surface, source)
            bindings[feedId] = SurfaceBinding(feedId, source, protectedContent, container, surfaceView = surface)
        }
        return container
    }

    fun update(feedId: String, player: EnginePlayerHandle, source: String) {
        bindings[feedId]?.let { attachExisting(it, player, source) }
    }

    fun detach(feedId: String, player: EnginePlayerHandle) {
        val binding = bindings.remove(feedId) ?: return
        binding.surfaceView?.let(player::clearVideoSurfaceView)
        binding.textureView?.let(player::clearVideoTextureView)
        binding.container.removeAllViews()
    }

    fun binding(feedId: String): SurfaceBinding? = bindings[feedId]

    fun clear() {
        bindings.clear()
    }

    private fun attachExisting(binding: SurfaceBinding, player: EnginePlayerHandle, source: String) {
        binding.textureView?.let(player::setVideoTextureView)
        binding.surfaceView?.let {
            player.setVideoSurfaceView(it)
            HdrSurfaceHints.apply(it, source)
            if (player.videoFormat?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) HdrSurfaceHints.applyHlg(it, source)
        }
    }
}
