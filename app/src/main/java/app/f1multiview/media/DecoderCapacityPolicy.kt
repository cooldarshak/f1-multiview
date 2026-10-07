package app.f1multiview.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes

/**
 * Conservative device-specific video decoder capacity.
 *
 * MediaCodec reports a maximum supported instance count as an upper-bound hint.
 * The value is not a guarantee, so the policy clamps the usable multiview budget
 * and falls back to the previously validated four-feed ceiling when the platform
 * cannot provide a useful capability hint.
 */
object DecoderCapacityPolicy {
    private const val FALLBACK_CAPACITY = 4
    private const val MIN_CAPACITY = 4
    private const val MAX_CAPACITY = 6

    internal fun clampCapacity(value: Int): Int = value.coerceIn(MIN_CAPACITY, MAX_CAPACITY)

    fun detect(): Int {
        if (Build.VERSION.SDK_INT < 23) return FALLBACK_CAPACITY

        return runCatching {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            val supportedInstances = codecs
                .asSequence()
                .filterNot(MediaCodecInfo::isEncoder)
                .filter { info ->
                    info.supportedTypes.any { type ->
                        type.equals(MimeTypes.VIDEO_H264, true) ||
                            type.equals(MimeTypes.VIDEO_H265, true)
                    }
                }
                .filter { info ->
                    if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else true
                }
                .mapNotNull { info ->
                    val type = info.supportedTypes.firstOrNull { type ->
                        type.equals(MimeTypes.VIDEO_H264, true) ||
                            type.equals(MimeTypes.VIDEO_H265, true)
                    } ?: return@mapNotNull null
                    val capabilities = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                        ?: return@mapNotNull null
                    val secure = runCatching {
                        capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                    }.getOrDefault(false)
                    val instances = capabilities.getMaxSupportedInstances()
                    if (secure && instances > 0) instances else null
                }
                .maxOrNull()

            if (supportedInstances == null) {
                FALLBACK_CAPACITY
            } else {
                clampCapacity(supportedInstances)
            }
        }.getOrDefault(FALLBACK_CAPACITY)
    }
}
