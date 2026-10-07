package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTiledMseControllerTest {
    @Test
    fun smallDriftUsesRateCorrection() {
        val c = OpenTiledMseController()
        val action = c.reconcile(1040, 1000)
        assertTrue(action is OpenTiledMseAction.SetPlaybackRate)
        assertEquals(1.0333333f, (action as OpenTiledMseAction.SetPlaybackRate).rate, 0.0001f)
    }

    @Test
    fun largeDriftUsesSeek() {
        val c = OpenTiledMseController()
        assertEquals(OpenTiledMseAction.Seek(2000), c.reconcile(2000, 1000))
    }

    @Test
    fun stableClockReturnsUnityRate() {
        val c = OpenTiledMseController()
        assertEquals(OpenTiledMseAction.SetPlaybackRate(1f), c.reconcile(1000, 990))
    }
    @Test
    fun negativeSmallDriftSlowsPlayback() {
        val action = OpenTiledMseController().reconcile(1000, 1040)
        assertTrue(action is OpenTiledMseAction.SetPlaybackRate)
        assertEquals(0.9666667f, (action as OpenTiledMseAction.SetPlaybackRate).rate, 0.0001f)
    }

    @Test
    fun largeNegativeDriftSeeksToReference() {
        assertEquals(OpenTiledMseAction.Seek(1000), OpenTiledMseController().reconcile(1000, 1500))
    }

}
