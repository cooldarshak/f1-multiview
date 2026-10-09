package app.f1multiview.media

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import app.f1multiview.model.StreamSource
import java.util.UUID

/**
 * Non-invasive capability report for authorized F1 feed integration.
 *
 * This probes platform declarations only. It does not request a license, open a stream,
 * infer actual concurrent decoder capacity, or claim that protected output can be composited.
 * Protected output is reported as a candidate for independent secure SurfaceView layers managed
 * by Android; this is not a claim that concurrent sessions or frame presentation work on-device.
 */
internal object ProtectedFeedCompatibility {
    private val WIDEVINE_UUID: UUID = C.WIDEVINE_UUID

    data class Report(
        val manifestKind: String,
        val drmConfigured: Boolean,
        val widevineSecurityLevel: String,
        val secureDecoderCandidates: Int,
        val protectedComposition: String
    ) {
        fun toLogFields(): String =
            "manifest=$manifestKind drmConfigured=$drmConfigured widevineSecurityLevel=$widevineSecurityLevel " +
                "secureDecoderCandidates=$secureDecoderCandidates protectedComposition=$protectedComposition"
    }

    fun inspect(context: Context, stream: StreamSource): Report {
        val url = stream.url.orEmpty()
        val manifestKind = when {
            url.contains(".mpd", ignoreCase = true) -> "DASH"
            url.contains(".m3u8", ignoreCase = true) -> "HLS"
            url.isBlank() -> "MISSING"
            else -> "OTHER"
        }
        val drmConfigured = DrmProtectionPolicy.requiresProtectedOutput(stream)
        return Report(
            manifestKind = manifestKind,
            drmConfigured = drmConfigured,
            widevineSecurityLevel = widevineSecurityLevel(),
            secureDecoderCandidates = countSecureDecoderCandidates(),
            protectedComposition = "SECURE_SURFACEVIEW_LAYERING_CANDIDATE_NOT_RUNTIME_VERIFIED"
        )
    }

    private fun widevineSecurityLevel(): String {
        if (Build.VERSION.SDK_INT < 18) return "API_UNAVAILABLE"
        var drm: MediaDrm? = null
        return try {
            drm = MediaDrm(WIDEVINE_UUID)
            runCatching { drm.getPropertyString("securityLevel") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
        } catch (t: Throwable) {
            "UNAVAILABLE_${t.javaClass.simpleName}"
        } finally {
            runCatching { drm?.release() }
        }
    }

    private fun countSecureDecoderCandidates(): Int = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filterNot(MediaCodecInfo::isEncoder)
            .filter { info ->
                info.supportedTypes.any { type ->
                    type.equals(MimeTypes.VIDEO_H264, true) ||
                        type.equals(MimeTypes.VIDEO_H265, true) ||
                        type.equals(MimeTypes.VIDEO_AV1, true)
                }
            }
            .count { info ->
                info.supportedTypes.any { type ->
                    (type.equals(MimeTypes.VIDEO_H264, true) ||
                        type.equals(MimeTypes.VIDEO_H265, true) ||
                        type.equals(MimeTypes.VIDEO_AV1, true)) &&
                        runCatching {
                            info.getCapabilitiesForType(type)
                                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                        }.getOrDefault(false)
                }
            }
    }.getOrDefault(0)
}
