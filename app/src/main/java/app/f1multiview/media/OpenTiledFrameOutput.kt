package app.f1multiview.media

/**
 * Explicit logical-view mapping layer corresponding to the concepts observed in
 * the production renderer. This is an original, provider-neutral model.
 */
data class OpenTiledDisplayObjectMapping(
    val viewId: String,
    val displayObjectId: Int,
    val feedId: String,
    val decoderId: String,
    val tileIndex: Int,
    val sourceRect: OpenTiledSourceRect,
    val secure: Boolean,
    val widthPx: Int,
    val heightPx: Int
)

data class OpenTiledFrameOutput(
    val positionMs: Long,
    val activeDecoderIds: List<String>,
    val displayMappings: List<OpenTiledDisplayObjectMapping>,
    val selectedFeedIds: List<String>
) {
    val activeDecoderCount: Int get() = activeDecoderIds.size
}
