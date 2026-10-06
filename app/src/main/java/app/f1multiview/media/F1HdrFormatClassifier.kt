package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.Format

object F1HdrFormatClassifier {
    fun isUhdHlg(format: Format, codecMime: String): Boolean {
        val descriptor = listOfNotNull(format.id, format.label, format.codecs).joinToString(" ")
        val hevc = format.sampleMimeType.equals("video/hevc", true) ||
            codecMime.equals("video/hevc", true) ||
            descriptor.contains("hvc", true) ||
            descriptor.contains("HEVC", true)
        val uhd = (format.width >= 3000 && format.height >= 1600) ||
            descriptor.contains("2160", true) ||
            descriptor.contains("UHD", true)
        val hlg = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG ||
            descriptor.contains("HLG", true) ||
            descriptor.contains("HDR", true)
        return hevc && uhd && hlg
    }
}
