package app.f1multiview.media

/**
 * Classifies DRM/session failures separately from retryable source failures.
 *
 * Media3's DRM errors can include licence acquisition, provisioning, key status and protected
 * content failures. These must remain visible rather than being retried as ordinary CDN errors
 * or hidden behind a quality downgrade. This classifier never changes Widevine security level
 * and never creates a clear-playback fallback.
 */
internal object PlaybackFailurePolicy {
    enum class FailureKind {
        DRM_FATAL,
        DECODER_RECOVERY,
        SOURCE_RECOVERY
    }

    fun classify(
        errorCodeName: String?,
        message: String?,
        decoderFailure: Boolean,
        cause: Throwable? = null
    ): FailureKind =
        when {
            isDrmFailure(errorCodeName, message, cause) -> FailureKind.DRM_FATAL
            decoderFailure -> FailureKind.DECODER_RECOVERY
            else -> FailureKind.SOURCE_RECOVERY
        }

    /**
     * Media3 may wrap a DRM/session exception in a renderer or playback exception whose top-level
     * code looks like a decoder failure. Inspect a bounded cause chain so wrapping cannot turn a
     * fatal licence/session failure into automatic decoder recovery.
     */
    fun isDrmFailure(
        errorCodeName: String?,
        message: String?,
        cause: Throwable? = null
    ): Boolean {
        if (matchesDrmSignal(errorCodeName) || matchesDrmSignal(message)) return true
        var current = cause
        var depth = 0
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        while (current != null && depth < 16 && visited.add(current)) {
            if (matchesDrmSignal(current.javaClass.name) || matchesDrmSignal(current.message)) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    private fun matchesDrmSignal(value: String?): Boolean {
        val signal = value.orEmpty()
        return signal.contains("DRM", ignoreCase = true) ||
            signal.contains("LICENSE", ignoreCase = true) ||
            signal.contains("KEY_EXPIRED", ignoreCase = true) ||
            signal.contains("DrmSession", ignoreCase = true) ||
            signal.contains("MediaDrm", ignoreCase = true) ||
            signal.contains("license acquisition", ignoreCase = true) ||
            signal.contains("Widevine", ignoreCase = true)
    }
}
