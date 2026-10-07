package app.f1multiview.media

import app.f1multiview.core.playback.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityManagerTest {
    private val manager = QualityManager()

    @Test
    fun autoBudgetProtectsReferenceAndTightensSecondaries() {
        assertEquals(QualityManager.Budget(Int.MAX_VALUE, Int.MAX_VALUE), manager.autoBudget(true, 4, 4))
        assertEquals(QualityManager.Budget(854, 480), manager.autoBudget(false, 1, 4))
        assertEquals(QualityManager.Budget(640, 360), manager.autoBudget(false, 4, 4))
    }

    @Test
    fun recoveryQualityLowersOnlyAsMuchAsNeeded() {
        assertEquals(Quality.FHD, manager.recoveryQuality(Quality.UHD, true))
        assertEquals(Quality.HD, manager.recoveryQuality(Quality.FHD, false))
        assertEquals(Quality.SD, manager.recoveryQuality(Quality.HD, false))
        assertEquals(Quality.SD, manager.recoveryQuality(Quality.AUTO, false))
        assertEquals(Quality.FHD, manager.recoveryQuality(Quality.AUTO, true))
        assertEquals(Quality.AUTO, manager.recoveryQuality(Quality.SD, false))
    }

    @Test
    fun minimumHeightsMatchQualityLabels() {
        assertEquals(2160, manager.minimumHeight(Quality.UHD))
        assertEquals(1080, manager.minimumHeight(Quality.FHD))
        assertEquals(720, manager.minimumHeight(Quality.HD))
        assertEquals(480, manager.minimumHeight(Quality.SD))
        assertEquals(0, manager.minimumHeight(Quality.AUTO))
    }

    @Test
    fun autoIsAlwaysAvailableEvenWithoutVideoRenditions() {
        val tracks = androidx.media3.common.Tracks.EMPTY
        assertTrue(manager.qualityAvailable(tracks, Quality.AUTO))
        assertFalse(manager.qualityAvailable(tracks, Quality.HD))
    }
}
