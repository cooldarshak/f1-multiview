package app.f1multiview.media

import androidx.media3.common.Format
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class F1HdrFormatClassifierTest {
    @Test
    fun detectsUhdHlgHevc() {
        val format = Format.Builder()
            .setSampleMimeType("video/hevc")
            .setWidth(3840)
            .setHeight(2160)
            .setId("2160p HLG")
            .build()
        assertTrue(F1HdrFormatClassifier.isUhdHlg(format, "video/hevc"))
    }

    @Test
    fun rejectsUhdSdr() {
        val format = Format.Builder()
            .setSampleMimeType("video/hevc")
            .setWidth(3840)
            .setHeight(2160)
            .build()
        assertFalse(F1HdrFormatClassifier.isUhdHlg(format, "video/hevc"))
    }

    @Test
    fun rejectsFhdHlg() {
        val format = Format.Builder()
            .setSampleMimeType("video/hevc")
            .setWidth(1920)
            .setHeight(1080)
            .setId("1080p HLG")
            .build()
        assertFalse(F1HdrFormatClassifier.isUhdHlg(format, "video/hevc"))
    }
}
