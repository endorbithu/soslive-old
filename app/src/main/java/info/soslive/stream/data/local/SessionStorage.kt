package info.soslive.stream.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import info.soslive.stream.data.remote.dto.SessionDto
import info.soslive.stream.data.remote.dto.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val user: UserDto,
)

fun SessionDto.toStored() = StoredSession(accessToken, refreshToken, user)

/** Persists the logged in session. Interface so networking code can be unit tested with a fake. */
interface SessionStorage {
    val session: Flow<StoredSession?>
    suspend fun current(): StoredSession? = session.first()
    suspend fun save(session: StoredSession)
    suspend fun updateUser(user: UserDto)
    suspend fun clear()
}

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

@Singleton
class DataStoreSessionStorage @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) : SessionStorage {

    private val dataStore = context.sessionDataStore

    override val session: Flow<StoredSession?> = dataStore.data
        .map { prefs -> prefs[KEY]?.let { runCatching { json.decodeFromString<StoredSession>(it) }.getOrNull() } }
        .distinctUntilChanged()

    override suspend fun save(session: StoredSession) {
        dataStore.edit { it[KEY] = json.encodeToString(StoredSession.serializer(), session) }
    }

    override suspend fun updateUser(user: UserDto) {
        dataStore.edit { prefs ->
            val current = prefs[KEY]?.let { runCatching { json.decodeFromString<StoredSession>(it) }.getOrNull() }
            if (current != null) prefs[KEY] = json.encodeToString(StoredSession.serializer(), current.copy(user = user))
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("session")
    }
}
