package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewSession
import app.f1multiview.data.f1tv.TmeTopology
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Open-source physical/logical playback plan.
 *
 * Inspired by the separation visible in the production APK between decoder resources,
 * display-object mappings and rendered frame state. This implementation is original.
 *
 * A single mosaic source has exactly one physical decoder. Logical feeds are mapped
 * to source rectangles and can be rearranged without allocating another decoder.
 */
data class OpenTiledSourceRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class OpenTiledFeedBinding(
    val feedId: String,
    val logicalIndex: Int,
    val physicalDecoderId: String,
    val sourceRect: OpenTiledSourceRect
)

data class OpenTiledDecoderPlan(
    val physicalDecoderIds: List<String>,
    val bindings: List<OpenTiledFeedBinding>,
    val tileColumns: Int,
    val tileRows: Int,
    val tileWidthPx: Int,
    val tileHeightPx: Int
) {
    val physicalDecoderCount: Int get() = physicalDecoderIds.size
    val logicalFeedCount: Int get() = bindings.size

    fun binding(feedId: String): OpenTiledFeedBinding? =
        bindings.firstOrNull { it.feedId == feedId }

    fun selectedBindings(feedIds: List<String>): List<OpenTiledFeedBinding> =
        feedIds.mapNotNull(::binding)

    companion object {
        fun from(
            session: TiledMultiviewSession,
            sourceVideoWidth: Int = 0,
            sourceVideoHeight: Int = 0
        ): OpenTiledDecoderPlan? {
            if (session.feeds.size < 2 || !session.hasTileGeometry) return null
            val urls = session.feeds.mapNotNull { it.url?.takeIf(String::isNotBlank) }.distinct()
            if (urls.size != 1) return null

            val tileWidth = session.tileWidth ?: return null
            val tileHeight = session.tileHeight ?: return null

            val columns = if (sourceVideoWidth >= tileWidth) {
                (sourceVideoWidth / tileWidth).coerceAtLeast(1)
            } else {
                ceil(sqrt(session.feeds.size.toDouble())).toInt().coerceAtLeast(1)
            }
            val rows = if (sourceVideoHeight >= tileHeight) {
                (sourceVideoHeight / tileHeight).coerceAtLeast(1)
            } else {
                ceil(session.feeds.size.toDouble() / columns).toInt().coerceAtLeast(1)
            }

            val bindings = session.feeds.mapIndexed { index, feed ->
                val feedId = session.feedIds[index]
                val column = index % columns
                val row = index / columns
                OpenTiledFeedBinding(
                    feedId = feedId,
                    logicalIndex = index,
                    physicalDecoderId = "tme-mosaic-decoder",
                    sourceRect = OpenTiledSourceRect(
                        left = column.toFloat() / columns,
                        top = row.toFloat() / rows,
                        right = (column + 1).toFloat() / columns,
                        bottom = (row + 1).toFloat() / rows
                    )
                )
            }

            return OpenTiledDecoderPlan(
                physicalDecoderIds = listOf("tme-mosaic-decoder"),
                bindings = bindings,
                tileColumns = columns,
                tileRows = rows,
                tileWidthPx = tileWidth,
                tileHeightPx = tileHeight
            )
        }
    }
}
