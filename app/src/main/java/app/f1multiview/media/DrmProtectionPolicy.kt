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

/**
 * Fail-closed admission contract for the future app-owned sample-to-decoder boundary.
 *
 * This deliberately does not create a DRM session or prove secure output. The caller must pass
 * observed runtime state; it must never treat codec capability discovery as proof that a secure
 * decoder is configured or that a protected Surface is valid.
 */
internal object ProtectedSampleAdmission {
    enum class Result {
        ACCEPT_CLEAR_SAMPLE,
        ACCEPT_PROTECTED_SAMPLE,
        REJECT_MISSING_LICENSE,
        REJECT_DRM_SESSION_NOT_READY,
        REJECT_SECURE_DECODER_UNAVAILABLE,
        REJECT_PROTECTED_SURFACE_UNAVAILABLE
    }

    fun evaluate(
        streamProtected: Boolean,
        sampleEncrypted: Boolean,
        licenseConfigured: Boolean,
        drmSessionReady: Boolean,
        secureDecoderConfigured: Boolean,
        protectedSurfaceValid: Boolean
    ): Result {
        if (!streamProtected && !sampleEncrypted) return Result.ACCEPT_CLEAR_SAMPLE
        if (!licenseConfigured) return Result.REJECT_MISSING_LICENSE
        if (!drmSessionReady) return Result.REJECT_DRM_SESSION_NOT_READY
        if (!secureDecoderConfigured) return Result.REJECT_SECURE_DECODER_UNAVAILABLE
        if (!protectedSurfaceValid) return Result.REJECT_PROTECTED_SURFACE_UNAVAILABLE
        return if (sampleEncrypted || streamProtected) {
            Result.ACCEPT_PROTECTED_SAMPLE
        } else {
            Result.ACCEPT_CLEAR_SAMPLE
        }
    }
}
