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
    private val setupKey = stringPreferencesKey("saved_setup")
    val setup: Flow<SavedSetup?> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[setupKey] ?: return@map null
        val parts = raw.split('|', limit = 4)
        if (parts.size < 4) return@map null
        val layout = runCatching { LayoutPreset.valueOf(parts[1]) }.getOrNull() ?: return@map null
        val name = parts[2].replace("%7C", "|")
        val ids = parts[3].split(',').filter(String::isNotBlank)
        SavedSetup(parts[0], name, layout, ids)
    }
    suspend fun save(setup: SavedSetup) {
        context.settingsDataStore.edit { prefs ->
            prefs[setupKey] = listOf(setup.id, setup.layout.name, setup.name.replace("|", "%7C"), setup.streamIds.joinToString(",")).joinToString("|")
        }
    }
    suspend fun clear() = context.settingsDataStore.edit { it.remove(setupKey) }
}
