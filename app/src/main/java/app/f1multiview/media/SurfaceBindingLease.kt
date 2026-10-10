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

    /** Identity requirements for reusing an existing Android output surface. */
    data class ReuseIdentity(
        val container: Any,
        val owner: Any,
        val protectedContent: Boolean
    )

    fun canReuse(current: ReuseIdentity?, requested: ReuseIdentity): Boolean =
        current != null &&
            current.container === requested.container &&
            current.owner === requested.owner &&
            current.protectedContent == requested.protectedContent

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
