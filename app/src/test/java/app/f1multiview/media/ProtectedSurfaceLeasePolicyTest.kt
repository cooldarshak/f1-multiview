package app.f1multiview.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedSurfaceLeasePolicyTest {
    private val surface = Any()

    @Test
    fun acceptsOnlyTheCurrentSecureProtectedDetachedSurface() {
        assertTrue(policy())
    }

    @Test
    fun rejectsStaleGenerationAndReplacedSurface() {
        assertFalse(policy(currentGeneration = 8L))
        assertFalse(policy(currentSurface = Any()))
    }

    @Test
    fun rejectsUnprotectedAttachedOrInvalidSurface() {
        assertFalse(policy(secureFlagRequested = false))
        assertFalse(policy(protectedContent = false))
        assertFalse(policy(playerOutputAttached = true))
        assertFalse(policy(viewAttached = false))
        assertFalse(policy(surfaceValid = false))
    }

    private fun policy(
        leaseGeneration: Long = 9L,
        currentGeneration: Long? = 9L,
        leaseSurface: Any = surface,
        currentSurface: Any? = surface,
        secureFlagRequested: Boolean = true,
        protectedContent: Boolean = true,
        playerOutputAttached: Boolean = false,
        viewAttached: Boolean = true,
        surfaceValid: Boolean = true
    ) = ProtectedSurfaceLeasePolicy.isCurrent(
        leaseGeneration,
        currentGeneration,
        leaseSurface,
        currentSurface,
        secureFlagRequested,
        protectedContent,
        playerOutputAttached,
        viewAttached,
        surfaceValid
    )
}
