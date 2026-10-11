package app.f1multiview.data.timing

import app.f1multiview.model.TrackDriverPosition

/**
 * Draws live position samples on a short fixed presentation delay so the UI can interpolate
 * between two actual received samples instead of inventing a path. Replay positions are already
 * sampled at the requested video playhead and should not be wall-clock interpolated here.
 */
internal fun interpolateLivePosition(
    driver: TrackDriverPosition,
    frameTimeMs: Long,
    presentationDelayMs: Long = 80L,
    maxSampleGapMs: Long = 2_500L
): TrackDriverPosition {
    val fromX = driver.previousX ?: return driver
    val fromY = driver.previousY ?: return driver
    val startMs = driver.previousUpdatedAtMs
    val endMs = driver.updatedAtMs
    val gapMs = endMs - startMs
    if (startMs <= 0L || gapMs !in 1L..maxSampleGapMs) return driver

    val targetMs = frameTimeMs - presentationDelayMs
    val fraction = ((targetMs - startMs).toDouble() / gapMs.toDouble()).coerceIn(0.0, 1.0)
    return driver.copy(
        x = fromX + (driver.x - fromX) * fraction,
        y = fromY + (driver.y - fromY) * fraction
    )
}

internal fun isLivePositionStale(
    driver: TrackDriverPosition,
    nowMs: Long,
    staleAfterMs: Long = 2_500L
): Boolean = driver.updatedAtMs <= 0L || nowMs - driver.updatedAtMs > staleAfterMs
