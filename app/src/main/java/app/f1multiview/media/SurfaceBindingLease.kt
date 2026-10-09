package app.f1multiview.media

/**
 * Identity check for AndroidView release callbacks. Container and owner comparisons are
 * referential by design: equal-looking replacements are not the same binding lifetime.
 */
internal object SurfaceBindingLease {
    data class Identity(
        val generation: Long,
        val container: Any,
        val owner: Any
    )

    fun matches(
        current: Identity?,
        releasedGeneration: Long?,
        releasedContainer: Any,
        releasedOwner: Any
    ): Boolean = current != null &&
        releasedGeneration != null &&
        current.generation == releasedGeneration &&
        current.container === releasedContainer &&
        current.owner === releasedOwner
}
