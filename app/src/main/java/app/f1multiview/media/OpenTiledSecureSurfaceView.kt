package app.f1multiview.media

import android.content.Context
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import app.f1multiview.core.playback.TiledMultiviewSession
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Secure-output counterpart of OpenTiledCompositorView.
 *
 * It intentionally leaves the decoded mosaic in a secure SurfaceView. It can
 * present the complete mosaic or focus one logical tile by changing the
 * surface buffer/view framing, but it never copies protected frames into app
 * memory or an OpenGL texture.
 */
class OpenTiledSecureSurfaceView(context: Context) : SurfaceView(context) {
    interface Listener {
        fun onSurfaceReady(surface: Surface)
        fun onSurfaceReleased()
    }

    private var listener: Listener? = null
    private var session: TiledMultiviewSession? = null
    private var focusedIndex: Int? = null

    init {
        setSecure(true)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            setSurfaceLifecycle(SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                listener?.onSurfaceReady(holder.surface)
                applyFocus()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                applyFocus()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                listener?.onSurfaceReleased()
            }
        })
    }

    fun setListener(value: Listener?) {
        listener = value
        if (value != null && holder.surface.isValid) {
            value.onSurfaceReady(holder.surface)
        }
    }

    fun clearOutputSurface() {
        listener?.onSurfaceReleased()
    }

    fun setSession(value: TiledMultiviewSession) {
        session = value
        focusedIndex = null
        requestLayout()
        applyFocus()
    }

    fun focusFeed(feedId: String?) {
        val s = session ?: return
        focusedIndex = feedId?.let { id ->
            s.feedIds.indexOf(id)
                .takeIf { it >= 0 }
        }
        applyFocus()
    }

    fun showFullMosaic() {
        focusedIndex = null
        applyFocus()
    }

    private fun applyFocus() {
        val s = session ?: return
        val tileWidth = s.tileWidth ?: return
        val tileHeight = s.tileHeight ?: return
        val plan = OpenTiledDecoderPlan.from(s) ?: return
        val columns = plan.tileColumns
        val rows = plan.tileRows

        if (focusedIndex == null) {
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
            clipBounds = null
            return
        }

        val index = focusedIndex ?: return
        val column = index % columns
        val row = index / columns

        // The secure surface remains the producer. Framing is performed by the
        // Android view/surface geometry rather than sampling decoded pixels.
        val scale = maxOf(width.toFloat() / tileWidth, height.toFloat() / tileHeight).coerceAtLeast(1f)
        scaleX = columns * scale
        scaleY = rows * scale
        translationX = -column * tileWidth * scale +
            (width - tileWidth * scale) / 2f
        translationY = -row * tileHeight * scale +
            (height - tileHeight * scale) / 2f
        clipBounds = Rect(0, 0, width, height)
    }
}
