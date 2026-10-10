package app.f1multiview.media

import android.view.Surface

/** A generation-bound lease for a protected SurfaceView output; never a GLES/TextureView target. */
internal data class ProtectedSurfaceLease(
    val feedId: String,
    val generation: Long,
    val surface: Surface,
    val secureFlagRequested: Boolean,
    val width: Int,
    val height: Int
)

/** Pure identity/state policy so stale or downgraded surface leases can be regression-tested. */
internal object ProtectedSurfaceLeasePolicy {
    fun isCurrent(
        leaseGeneration: Long,
        currentGeneration: Long?,
        leaseSurface: Any,
        currentSurface: Any?,
        secureFlagRequested: Boolean,
        protectedContent: Boolean,
        playerOutputAttached: Boolean,
        viewAttached: Boolean,
        surfaceValid: Boolean
    ): Boolean =
        secureFlagRequested &&
            protectedContent &&
            !playerOutputAttached &&
            viewAttached &&
            surfaceValid &&
            currentGeneration == leaseGeneration &&
            currentSurface === leaseSurface
}
