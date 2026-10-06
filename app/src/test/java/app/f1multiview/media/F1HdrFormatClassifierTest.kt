package app.f1multiview.media

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
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
            .setColorInfo(ColorInfo(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_HLG))
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
            .setColorInfo(ColorInfo(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_HLG))
            .build()
        assertFalse(F1HdrFormatClassifier.isUhdHlg(format, "video/hevc"))
    }
}
