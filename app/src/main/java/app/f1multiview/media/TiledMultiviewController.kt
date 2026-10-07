package app.f1multiview.media

import app.f1multiview.core.playback.TiledMultiviewFeed
import app.f1multiview.core.playback.TiledMultiviewSession

/**
 * Device-side state machine for a TME multiview session.
 *
 * It owns logical feed selection/layout/audio state, but deliberately does not
 * create Media3 players. The production F1/Tiledmedia architecture separates
 * this logical state from the single native multistream decoder.
 */
class TiledMultiviewController {
    data class ViewSlot(
        val feedId: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val zIndex: Int = 0
    )

    data class State(
        val session: TiledMultiviewSession? = null,
        val slots: List<ViewSlot> = emptyList(),
        val focusedFeedId: String? = null,
        val audioFeedId: String? = null
    )

    private var state = State()

    fun state(): State = state

    fun configure(session: TiledMultiviewSession) {
        val existingIds = state.slots.map { it.feedId }.filter { it in session.feedIds }
        // This is logical TME state, not physical decoder allocation.
        // Keep every feed addressable here. The active playback backend decides
        // how many physical resources can actually render at once.
        val selected = if (existingIds.isNotEmpty()) existingIds else session.feedIds
        state = state.copy(
            session = session,
            slots = uniformGrid(selected),
            focusedFeedId = selected.firstOrNull(),
            audioFeedId = selected.firstOrNull()
        )
    }

    fun setFeeds(feedIds: List<String>) {
        val session = state.session ?: return
        val valid = feedIds.distinct().filter { it in session.feedIds }
        state = state.copy(
            slots = uniformGrid(valid),
            focusedFeedId = state.focusedFeedId?.takeIf { it in valid } ?: valid.firstOrNull(),
            audioFeedId = state.audioFeedId?.takeIf { it in valid } ?: valid.firstOrNull()
        )
    }

    fun focus(feedId: String) {
        if (feedId in state.slots.map { it.feedId }) {
            state = state.copy(focusedFeedId = feedId)
        }
    }

    fun setAudio(feedId: String) {
        if (feedId in state.slots.map { it.feedId }) {
            state = state.copy(audioFeedId = feedId, focusedFeedId = feedId)
        }
    }

    fun setSlots(slots: List<ViewSlot>) {
        val validIds = state.session?.feedIds?.toSet().orEmpty()
        val valid = slots.filter { it.feedId in validIds }
        state = state.copy(
            slots = valid,
            focusedFeedId = state.focusedFeedId?.takeIf { id -> valid.any { it.feedId == id } }
                ?: valid.firstOrNull()?.feedId,
            audioFeedId = state.audioFeedId?.takeIf { id -> valid.any { it.feedId == id } }
                ?: valid.firstOrNull()?.feedId
        )
    }

    fun clear() {
        state = State()
    }

    private fun uniformGrid(feedIds: List<String>): List<ViewSlot> {
        if (feedIds.isEmpty()) return emptyList()
        val columns = when {
            feedIds.size == 1 -> 1
            feedIds.size <= 4 -> 2
            else -> 3
        }
        val rows = (feedIds.size + columns - 1) / columns
        return feedIds.mapIndexed { index, id ->
            val row = index / columns
            val column = index % columns
            ViewSlot(
                feedId = id,
                x = column.toFloat() / columns,
                y = row.toFloat() / rows,
                width = 1f / columns,
                height = 1f / rows,
                zIndex = index
            )
        }
    }
}
