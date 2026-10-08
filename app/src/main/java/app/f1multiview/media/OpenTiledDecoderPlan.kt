package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewSession
import app.f1multiview.data.f1tv.TmeTopology
import kotlin.math.abs
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
            val tileWidth = session.tileWidth ?: return null
            val tileHeight = session.tileHeight ?: return null

            val urls = session.feeds.mapNotNull { it.url?.takeIf(String::isNotBlank) }.distinct()
            val authoritativePlacement = session.feeds.all {
                it.tileRow != null && it.tileColumn != null
            }
            if (authoritativePlacement) {
                val columns = session.tileCountHorizontal
                    ?: (session.feeds.maxOf { requireNotNull(it.tileColumn) + 1 })
                val rows = session.tileCountVertical
                    ?: (session.feeds.maxOf { requireNotNull(it.tileRow) + 1 })
                val bindings = session.feeds.mapIndexed { index, feed ->
                    val feedId = session.feedIds[index]
                    val column = requireNotNull(feed.tileColumn)
                    val row = requireNotNull(feed.tileRow)
                    OpenTiledFeedBinding(
                        feedId = feedId,
                        logicalIndex = index,
                        physicalDecoderId = "tme-native-decoder",
                        sourceRect = OpenTiledSourceRect(
                            left = column.toFloat() / columns,
                            top = row.toFloat() / rows,
                            right = (column + 1).toFloat() / columns,
                            bottom = (row + 1).toFloat() / rows
                        )
                    )
                }
                return OpenTiledDecoderPlan(
                    physicalDecoderIds = listOf(
                        if (urls.size == 1) "tme-mosaic-decoder" else "tme-native-decoder"
                    ),
                    bindings = bindings,
                    tileColumns = columns,
                    tileRows = rows,
                    tileWidthPx = tileWidth,
                    tileHeightPx = tileHeight
                )
            }

            if (urls.size != 1) return null

            val feedCount = session.feeds.size
            val sourceAspect = if (sourceVideoWidth > 0 && sourceVideoHeight > 0) {
                sourceVideoWidth.toDouble() / sourceVideoHeight.toDouble()
            } else {
                tileWidth.toDouble() / tileHeight.toDouble()
            }

            // The feed count is authoritative for grid topology. Source dimensions
            // are used only to choose the closest valid factor pair. We never allow
            // a 24-feed session to be forced into a 4x4 grid just because the source
            // dimensions happen to divide into four nominal tiles.
            val factors = (1..ceil(sqrt(feedCount.toDouble())).toInt())
                .filter { feedCount % it == 0 }
                .flatMap { rowsCandidate ->
                    val columnsCandidate = feedCount / rowsCandidate
                    listOf(
                        columnsCandidate to rowsCandidate,
                        rowsCandidate to columnsCandidate
                    )
                }
                .distinct()

            val (columns, rows) = factors.minByOrNull { (candidateColumns, candidateRows) ->
                abs(
                    (candidateColumns * tileWidth).toDouble() /
                        (candidateRows * tileHeight).toDouble() - sourceAspect
                )
            } ?: (ceil(sqrt(feedCount.toDouble())).toInt().coerceAtLeast(1) to
                ceil(feedCount.toDouble() / ceil(sqrt(feedCount.toDouble())).toInt()).toInt())


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
