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
    fun isDrmFailure(errorCodeName: String?, message: String?): Boolean {
        val code = errorCodeName.orEmpty()
        val detail = message.orEmpty()
        return code.contains("DRM", ignoreCase = true) ||
            code.contains("LICENSE", ignoreCase = true) ||
            code.contains("KEY_EXPIRED", ignoreCase = true) ||
            detail.contains("DrmSession", ignoreCase = true) ||
            detail.contains("MediaDrm", ignoreCase = true) ||
            detail.contains("license acquisition", ignoreCase = true) ||
            detail.contains("Widevine", ignoreCase = true)
    }
}
