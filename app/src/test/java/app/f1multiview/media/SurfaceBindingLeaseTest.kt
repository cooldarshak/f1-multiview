package app.f1multiview.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceBindingLeaseTest {
    @Test
    fun sameContainerOwnerAndProtectionCanReuseSurfaceAcrossLayoutUpdates() {
        val container = Any()
        val owner = Any()
        val current = SurfaceBindingLease.ReuseIdentity(container, owner, true)
        val requested = SurfaceBindingLease.ReuseIdentity(container, owner, true)

        assertTrue(SurfaceBindingLease.canReuse(current, requested))
    }

    @Test
    fun replacementContainerOrOwnerOrProtectionRequiresNewBinding() {
        val container = Any()
        val replacementContainer = Any()
        val owner = Any()
        val replacementOwner = Any()
        val current = SurfaceBindingLease.ReuseIdentity(container, owner, true)

        assertFalse(SurfaceBindingLease.canReuse(current,
            SurfaceBindingLease.ReuseIdentity(replacementContainer, owner, true)))
        assertFalse(SurfaceBindingLease.canReuse(current,
            SurfaceBindingLease.ReuseIdentity(container, replacementOwner, true)))
        assertFalse(SurfaceBindingLease.canReuse(current,
            SurfaceBindingLease.ReuseIdentity(container, owner, false)))
        assertFalse(SurfaceBindingLease.canReuse(null,
            SurfaceBindingLease.ReuseIdentity(container, owner, true)))
    }

    @Test
    fun replacementRejectsReleaseFromOldContainer() {
        val owner = Any()
        val oldContainer = Any()
        val newContainer = Any()
        val current = SurfaceBindingLease.Identity(2L, newContainer, owner)

        assertFalse(SurfaceBindingLease.matches(current, 1L, oldContainer, owner))
        assertFalse(SurfaceBindingLease.matches(current, 1L, newContainer, owner))
        assertTrue(SurfaceBindingLease.matches(current, 2L, newContainer, owner))
    }

    @Test
    fun staleReleaseFromPreviousOwnerIsRejected() {
        val container = Any()
        val oldOwner = Any()
        val newOwner = Any()
        val current = SurfaceBindingLease.Identity(7L, container, newOwner)

        assertFalse(SurfaceBindingLease.matches(current, 7L, container, oldOwner))
        assertTrue(SurfaceBindingLease.matches(current, 7L, container, newOwner))
    }

    @Test
    fun cleanupOrMissingLeaseRejectsAnyRelease() {
        val container = Any()
        val owner = Any()

        assertFalse(SurfaceBindingLease.matches(null, 1L, container, owner))
        assertFalse(SurfaceBindingLease.matches(
            SurfaceBindingLease.Identity(1L, container, owner),
            null,
            container,
            owner
        ))
    }

    @Test
    fun reusedContainerStillRequiresTheCurrentGeneration() {
        val container = Any()
        val owner = Any()
        val current = SurfaceBindingLease.Identity(12L, container, owner)

        assertFalse(SurfaceBindingLease.matches(current, 11L, container, owner))
        assertTrue(SurfaceBindingLease.matches(current, 12L, container, owner))
    }
}
