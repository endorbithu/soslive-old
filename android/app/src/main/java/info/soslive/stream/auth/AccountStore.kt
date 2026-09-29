package info.soslive.stream.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import info.soslive.stream.drive.DriveCache
import info.soslive.stream.drive.DriveJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** The signed in Google user (only name + e-mail, no tokens). */
@Serializable
data class UserAccount(
    val email: String,
    val name: String,
    /** Development mode without Google: files are stored locally. */
    val simulated: Boolean,
)

enum class EventType { SOS, LIVE, PHOTO }

/** The incident the user is working on (2 h window for extra photos / messages). */
@Serializable
data class ActiveEvent(
    val fileId: String,
    val type: EventType,
    val title: String,
    val link: String,
    val startedAtMillis: Long,
) {
    fun isExpired(nowMillis: Long, windowMillis: Long): Boolean = nowMillis - startedAtMillis >= windowMillis
}

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "soslive")

/** Account, Drive folder id and a copy of config.json in DataStore. */
@Singleton
class AccountStore @Inject constructor(
    @ApplicationContext context: Context,
) : DriveCache {

    private val dataStore = context.appDataStore

    val account: Flow<UserAccount?> = dataStore.data
        .map { prefs -> prefs[ACCOUNT]?.let { runCatching { DriveJson.decodeFromString(UserAccount.serializer(), it) }.getOrNull() } }
        .distinctUntilChanged()

    suspend fun currentAccount(): UserAccount? = account.first()

    suspend fun setAccount(account: UserAccount?) {
        dataStore.edit { prefs ->
            if (account == null) prefs.clear() else prefs[ACCOUNT] = DriveJson.encodeToString(UserAccount.serializer(), account)
        }
    }

    override suspend fun folderId(): String? = dataStore.data.first()[FOLDER_ID]

    override suspend fun setFolderId(id: String?) {
        dataStore.edit { if (id == null) it.remove(FOLDER_ID) else it[FOLDER_ID] = id }
    }

    override suspend fun config(): ByteArray? =
        dataStore.data.first()[CONFIG]?.let { Base64.getDecoder().decode(it) }

    override suspend fun setConfig(bytes: ByteArray?) {
        dataStore.edit { if (bytes == null) it.remove(CONFIG) else it[CONFIG] = Base64.getEncoder().encodeToString(bytes) }
    }

    /** The open incident (photos / messages still go to it), if any. Expiry is checked by the caller. */
    val activeEvent: Flow<ActiveEvent?> = dataStore.data
        .map { prefs -> prefs[ACTIVE_EVENT]?.let { runCatching { DriveJson.decodeFromString(ActiveEvent.serializer(), it) }.getOrNull() } }
        .distinctUntilChanged()

    suspend fun setActiveEvent(event: ActiveEvent?) {
        dataStore.edit {
            if (event == null) it.remove(ACTIVE_EVENT) else it[ACTIVE_EVENT] = DriveJson.encodeToString(ActiveEvent.serializer(), event)
        }
    }

    private companion object {
        val ACTIVE_EVENT = stringPreferencesKey("active_event")
        val ACCOUNT = stringPreferencesKey("account")
        val FOLDER_ID = stringPreferencesKey("drive_folder_id")
        val CONFIG = stringPreferencesKey("config_json")
    }
}
