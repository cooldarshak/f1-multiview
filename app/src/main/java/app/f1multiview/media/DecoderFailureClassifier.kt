package app.f1multiview.media

object DecoderFailureClassifier {
    fun shouldTryWidevineL3(
        isMain: Boolean,
        playerCount: Int,
        hasDrmLicense: Boolean,
        errorCodeName: String,
        evidence: String
    ): Boolean {
        if (isMain || playerCount < 4 || !hasDrmLicense) return false
        val normalized = evidence.lowercase()
        val resourceEvidence = listOf(
            "resourcebusyexception",
            "insufficient resource",
            "insufficientresources",
            "resource busy",
            "too many",
            "resource exhausted",
            "resource limit",
            "secure decoder",
            "securedecoder",
            "omx.error.insufficientresources",
            "error_insufficient_resources"
        ).any(normalized::contains)
        return resourceEvidence ||
            (errorCodeName == "ERROR_CODE_DECODER_INIT_FAILED" && normalized.contains("mediacodec"))
    }
}
