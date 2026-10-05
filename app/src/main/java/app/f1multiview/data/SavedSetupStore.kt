package app.f1multiview.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.f1multiview.model.LayoutPreset
import app.f1multiview.model.SavedSetup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "f1_multiview")

class SavedSetupStore(private val context: Context) {
    private val setupsKey = stringPreferencesKey("saved_setups")
    private val legacyKey = stringPreferencesKey("saved_setup")

    val setups: Flow<List<SavedSetup>> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[setupsKey]
        if (!raw.isNullOrBlank()) raw.lines().mapNotNull(::decode).distinctBy { it.id }
        else listOfNotNull(prefs[legacyKey]?.let(::decode))
    }

    val setup: Flow<SavedSetup?> = setups.map { it.firstOrNull() }

    suspend fun save(setup: SavedSetup) {
        context.settingsDataStore.edit { prefs ->
            val existing = prefs[setupsKey].orEmpty().lines().mapNotNull(::decode).toMutableList()
            existing.removeAll { it.id == setup.id }
            existing.add(0, setup)
            prefs[setupsKey] = existing.joinToString("\n", transform = ::encode)
            prefs.remove(legacyKey)
        }
    }

    suspend fun delete(id: String) {
        context.settingsDataStore.edit { prefs ->
            val remaining = prefs[setupsKey].orEmpty().lines().mapNotNull(::decode).filterNot { it.id == id }
            if (remaining.isEmpty()) prefs.remove(setupsKey) else prefs[setupsKey] = remaining.joinToString("\n", transform = ::encode)
        }
    }

    suspend fun clear() = context.settingsDataStore.edit {
        it.remove(setupsKey)
        it.remove(legacyKey)
    }

    private fun encode(setup: SavedSetup): String =
        listOf(
            setup.id,
            setup.layout.name,
            setup.name.replace("%", "%25").replace("|", "%7C").replace("\n", " "),
            setup.streamIds.joinToString(","),
            setup.mainStreamId.orEmpty()
        ).joinToString("|")

    private fun decode(raw: String): SavedSetup? {
        val parts = raw.split('|', limit = 5)
        if (parts.size < 4) return null
        val layout = runCatching { LayoutPreset.valueOf(parts[1]) }.getOrNull() ?: return null
        val name = parts[2].replace("%7C", "|").replace("%25", "%")
        val ids = parts[3].split(',').filter(String::isNotBlank)
        val mainId = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
        return SavedSetup(parts[0], name, layout, ids, mainId)
    }
}
