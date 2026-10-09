package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewSession
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
            val tileWidth = session.tileWidth ?: return null
            val tileHeight = session.tileHeight ?: return null

            val authoritativePlacement = session.feeds.all {
                it.tileRow != null && it.tileColumn != null
            }
            if (!authoritativePlacement) return null

            val columns = session.tileCountHorizontal
                ?: (session.feeds.maxOf { requireNotNull(it.tileColumn) + 1 })
            val rows = session.tileCountVertical
                ?: (session.feeds.maxOf { requireNotNull(it.tileRow) + 1 })
            if (columns <= 0 || rows <= 0) return null
            if (session.feeds.any { feed ->
                    val column = requireNotNull(feed.tileColumn)
                    val row = requireNotNull(feed.tileRow)
                    column !in 0 until columns || row !in 0 until rows
                }
            ) return null

            // Explicit tile geometry is only valid for a precomposed mosaic when
            // the actual decoded source dimensions match the whole tile canvas.
            // URL equality or feed count alone must not invent a grid.
            if (sourceVideoWidth > 0 && sourceVideoHeight > 0 &&
                (sourceVideoWidth != columns * tileWidth || sourceVideoHeight != rows * tileHeight)
            ) {
                return null
            }
            val bindings = session.feeds.mapIndexed { index, feed ->
                val feedId = session.feedIds[index]
                val column = requireNotNull(feed.tileColumn)
                val row = requireNotNull(feed.tileRow)
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
