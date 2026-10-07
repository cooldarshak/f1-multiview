package app.f1multiview.media

import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderCapacityPolicyTest {
    @Test
    fun policyBoundsAreConservative() {
        val capacity = DecoderCapacityPolicy.detect()
        assertTrue(capacity in 4..6)
    }
}
