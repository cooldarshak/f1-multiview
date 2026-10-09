package app.f1multiview.media

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor

/**
 * Entry point for the app-owned fragmented-MP4 extraction spike.
 *
 * This only constructs the public Media3 container extractor. It deliberately does not resolve
 * manifests, fetch segments, create DRM sessions, decrypt samples, or configure secure decoders.
 * Those are separate stages and must not be inferred from successful extractor construction.
 */
@OptIn(UnstableApi::class)
internal object CmafExtractorPrototype {
    /**
     * Create a fresh extractor for a fragmented MP4 byte stream (initialization segment plus
     * media fragments). Instances are stateful and must not be shared across feed pipelines.
     */
    fun createExtractor(): Extractor = FragmentedMp4Extractor()

    /** A narrow admission policy; this is not manifest parsing or stream validation. */
    fun supportsContainerMimeType(mimeType: String?): Boolean =
        mimeType.equals("video/mp4", ignoreCase = true) ||
            mimeType.equals("application/mp4", ignoreCase = true)
}
