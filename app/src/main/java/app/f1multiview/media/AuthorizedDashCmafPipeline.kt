package app.f1multiview.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.f1multiview.model.StreamSource
import java.io.IOException

/**
 * Orchestrates the first authorized DASH CMAF segment from manifest to app-owned SampleQueues.
 *
 * A protected stream must be paired with AuthorizedWidevineSampleSession.sampleOutput. This class
 * does not create a fallback player, decrypt samples itself, or mark a feed playable after
 * extraction. It is a bounded first-segment diagnostic boundary until secure decoding is integrated.
 */
@OptIn(UnstableApi::class)
internal class AuthorizedDashCmafPipeline(
    private val stream: StreamSource,
    private val dataSourceFactory: DataSource.Factory = AuthorizedStreamDataSourceFactory.create(stream),
    private val maxWidth: Int = Int.MAX_VALUE,
    private val maxHeight: Int = Int.MAX_VALUE
) {
    data class Result(
        val resolvedManifestUri: Uri,
        val videoPlan: AuthorizedDashManifestResolver.VideoPlan,
        val extraction: AuthorizedCmafSegmentExtractor.Result,
        val extractedDrmInitDataPresent: Boolean
    )

    @Throws(IOException::class)
    fun prepareFirstSegment(output: CmafSampleQueueOutput): Result {
        val protected = DrmProtectionPolicy.requiresProtectedOutput(stream)
        if (protected && DrmProtectionPolicy.missingLicenseEndpoint(stream)) {
            throw IOException("Protected F1 stream has no authorized Widevine license endpoint")
        }
        if (protected && !output.isDrmManaged) {
            throw IOException("Protected F1 stream requires DRM-managed SampleQueues; refusing clear queues")
        }

        val manifestUrl = stream.url?.takeIf(String::isNotBlank)
            ?: throw IOException("Authorized DASH manifest URL is missing")
        val manifestSpec = DataSpec.Builder()
            .setUri(Uri.parse(manifestUrl))
            .setHttpRequestHeaders(stream.requestHeaders)
            .build()
        val manifest = BoundedDataSpecLoader.load(
            dataSourceFactory,
            manifestSpec,
            MAX_MANIFEST_BYTES
        )
        val plan = AuthorizedDashManifestResolver.resolve(
            manifestUri = manifest.resolvedUri,
            manifestBytes = manifest.bytes,
            requestHeaders = stream.requestHeaders,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            requireWidevineInitData = protected
        )
        val extraction = AuthorizedCmafSegmentExtractor(dataSourceFactory)
            .extractFirstSegment(plan, output)
        val hasDrmInitData = output.sampleQueues.values.any { it.upstreamFormat?.drmInitData != null }

        if (protected && !hasDrmInitData) {
            throw IOException("Protected CMAF extraction produced no DRM initialization data")
        }
        if (hasDrmInitData && !output.isDrmManaged) {
            throw IOException("CMAF contains DRM initialization data but no DRM-managed output was supplied")
        }

        return Result(
            resolvedManifestUri = manifest.resolvedUri,
            videoPlan = plan,
            extraction = extraction,
            extractedDrmInitDataPresent = hasDrmInitData
        )
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    }
}
