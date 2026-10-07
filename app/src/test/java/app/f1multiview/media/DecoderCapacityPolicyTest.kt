package app.f1multiview.media

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderCapacityPolicyTest {
    @Test
    fun capacityIsClampedToConservativeRange() {
        assertEquals(4, DecoderCapacityPolicy.clampCapacity(1))
        assertEquals(5, DecoderCapacityPolicy.clampCapacity(5))
        assertEquals(6, DecoderCapacityPolicy.clampCapacity(99))
    }
}
