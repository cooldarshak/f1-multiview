package app.f1multiview

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import app.f1multiview.media.AppLogger
import java.io.File

/** Clear fixture proof: two encoded tile inputs were merged into one HEVC stream off device. */
@OptIn(UnstableApi::class)
class SingleDecoderTileProofActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var status: TextView
    private var decoderInitializations = 0
    private var decoderName = "pending"
    private var firstFrameRendered = false
    private var error: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val playerView = PlayerView(this).apply { useController = true }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xD9000000.toInt())
            textSize = 13f
            setPadding(20, 12, 20, 12)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        renderStatus()

        val fixture = File(cacheDir, "single-decoder-hevc-tile-proof.mp4")
        assets.open("single-decoder-hevc-tile-proof.mp4").use { input ->
            fixture.outputStream().use(input::copyTo)
        }
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        playerView.player = exoPlayer
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                decoderInitializations++
                this@SingleDecoderTileProofActivity.decoderName = decoderName
                AppLogger.i("SingleDecoderTileProof", "videoDecoderInitialized count=$decoderInitializations codec=$decoderName")
                renderStatus()
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                firstFrameRendered = true
                renderStatus()
            }
        })
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) {
                error = playbackError.errorCodeName
                renderStatus()
            }
        })
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    private fun renderStatus() {
        status.text = buildString {
            append("CLEAR HEVC TILE PROOF · two inputs / one video track\n")
            append("Video decoder initializations: $decoderInitializations · $decoderName\n")
            append("First frame rendered: $firstFrameRendered")
            error?.let { append("\nPlayback error: $it") }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
