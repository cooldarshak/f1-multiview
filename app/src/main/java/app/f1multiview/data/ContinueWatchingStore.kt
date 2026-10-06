package app.f1multiview.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.f1multiview.model.ContinueWatchingEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.continueWatchingDataStore by preferencesDataStore(name = "f1_multiview_continue_watching")

/**
 * Persistent VOD resume history. Live sessions are intentionally never written here.
 *
 * The compact line format keeps this additive and migration-free while preserving
 * compatibility with the app's existing Preferences DataStore approach.
 */
class ContinueWatchingStore(private val context: Context) {
    private val entriesKey = stringPreferencesKey("entries")
    private val maxEntries = 12

    val entries: Flow<List<ContinueWatchingEntry>> =
        context.continueWatchingDataStore.data.map { prefs ->
            prefs[entriesKey].orEmpty()
                .lines()
                .mapNotNull(::decode)
                .sortedByDescending { it.updatedAtMs }
                .take(maxEntries)
        }

    suspend fun upsert(entry: ContinueWatchingEntry) {
        context.continueWatchingDataStore.edit { prefs ->
            val existing = prefs[entriesKey].orEmpty()
                .lines()
                .mapNotNull(::decode)
                .filterNot { it.contentId == entry.contentId }
                .toMutableList()
            existing.add(0, entry)
            prefs[entriesKey] = existing
                .sortedByDescending { it.updatedAtMs }
                .take(maxEntries)
                .joinToString("
", transform = ::encode)
        }
    }

    suspend fun remove(contentId: String) {
        context.continueWatchingDataStore.edit { prefs ->
            val remaining = prefs[entriesKey].orEmpty()
                .lines()
                .mapNotNull(::decode)
                .filterNot { it.contentId == contentId }
            if (remaining.isEmpty()) prefs.remove(entriesKey)
            else prefs[entriesKey] = remaining.joinToString("
", transform = ::encode)
        }
    }

    suspend fun clear() = context.continueWatchingDataStore.edit { it.remove(entriesKey) }

    private fun encode(entry: ContinueWatchingEntry): String =
        listOf(
            entry.contentId,
            entry.title,
            entry.series,
            entry.eventPageId.toString(),
            entry.stage,
            entry.positionMs.toString(),
            entry.durationMs.toString(),
            entry.updatedAtMs.toString(),
            entry.artworkUrl.orEmpty(),
            entry.backgroundArtworkUrl.orEmpty(),
            entry.seasonYear?.toString().orEmpty(),
            entry.meetingNumber?.toString().orEmpty(),
            entry.streamId.orEmpty()
        ).joinToString("|") { escape(it) }

    private fun decode(raw: String): ContinueWatchingEntry? {
        val parts = raw.split('|').map(::unescape)
        if (parts.size < 13) return null
        val contentId = parts[0].takeIf { it.isNotBlank() } ?: return null
        val title = parts[1]
        val eventPageId = parts[3].toIntOrNull() ?: return null
        val position = parts[5].toLongOrNull() ?: return null
        val duration = parts[6].toLongOrNull() ?: 0L
        val updated = parts[7].toLongOrNull() ?: 0L
        return ContinueWatchingEntry(
            contentId = contentId,
            title = title,
            series = parts[2].ifBlank { "F1" },
            eventPageId = eventPageId,
            stage = parts[4].ifBlank { "other" },
            positionMs = position.coerceAtLeast(0L),
            durationMs = duration.coerceAtLeast(0L),
            updatedAtMs = updated,
            artworkUrl = parts[8].ifBlank { null },
            backgroundArtworkUrl = parts[9].ifBlank { null },
            seasonYear = parts[10].toIntOrNull(),
            meetingNumber = parts[11].toIntOrNull(),
            streamId = parts[12].ifBlank { null }
        )
    }

    private fun escape(value: String): String =
        value.replace("%", "%25").replace("|", "%7C").replace("
", " ")

    private fun unescape(value: String): String =
        value.replace("%7C", "|").replace("%25", "%")
}
