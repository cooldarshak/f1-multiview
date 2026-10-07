package app.f1multiview.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RacingSeriesTest {
    @Test
    fun knownSeriesResolveCaseInsensitively() {
        assertEquals(RacingSeries.F1, RacingSeries.fromId("f1"))
        assertEquals(RacingSeries.F2, RacingSeries.fromId("F2"))
        assertEquals(RacingSeries.F1_ACADEMY, RacingSeries.fromId("f1 academy"))
        assertEquals(RacingSeries.PORSCHE_SUPERCUP, RacingSeries.fromId("Porsche Supercup"))
    }

    @Test
    fun unknownSeriesFallsBackToF1() {
        assertEquals(RacingSeries.F1, RacingSeries.fromId("unknown"))
        assertEquals(RacingSeries.F1, RacingSeries.fromId(null))
    }
}
