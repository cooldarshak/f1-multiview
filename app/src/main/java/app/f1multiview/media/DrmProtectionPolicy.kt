package app.f1multiview.media

import app.f1multiview.model.StreamSource

/** Conservative policy for deciding whether a feed must use a protected output. */
internal object DrmProtectionPolicy {
    /** Pipeline version alone is deliberately not treated as a DRM declaration. */
    fun requiresProtectedOutput(stream: StreamSource): Boolean =
        stream.drmProtected ||
            !stream.drmLicenseUrl.isNullOrBlank() ||
            stream.drmType?.contains("widevine", ignoreCase = true) == true ||
            stream.streamType?.contains("DASHWV", ignoreCase = true) == true

    fun missingLicenseEndpoint(stream: StreamSource): Boolean =
        requiresProtectedOutput(stream) && stream.drmLicenseUrl.isNullOrBlank()
}
