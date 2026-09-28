package info.soslive.stream.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import info.soslive.stream.domain.model.ActiveEvent
import info.soslive.stream.domain.model.EventType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface ActiveEventStorage {
    val activeEvent: Flow<ActiveEvent?>
    suspend fun save(event: ActiveEvent)
    suspend fun clear()
}

@Serializable
private data class StoredActiveEvent(val id: Long, val type: String, val startedAtMillis: Long, val shareUrl: String)

private val Context.eventsDataStore: DataStore<Preferences> by preferencesDataStore(name = "events")

@Singleton
class DataStoreActiveEventStorage @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) : ActiveEventStorage {

    private val dataStore = context.eventsDataStore

    override val activeEvent: Flow<ActiveEvent?> = dataStore.data.map { prefs ->
        prefs[KEY]?.let { raw ->
            runCatching {
                val stored = json.decodeFromString(StoredActiveEvent.serializer(), raw)
                ActiveEvent(stored.id, EventType.valueOf(stored.type), stored.startedAtMillis, stored.shareUrl)
            }.getOrNull()
        }
    }

    override suspend fun save(event: ActiveEvent) {
        val stored = StoredActiveEvent(event.id, event.type.name, event.startedAtMillis, event.shareUrl)
        dataStore.edit { it[KEY] = json.encodeToString(StoredActiveEvent.serializer(), stored) }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("active_event")
    }
}
