package info.soslive.stream.stream

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.streamDataStore: DataStore<Preferences> by preferencesDataStore(name = "stream_settings")

/**
 * The user's streaming settings, only on this phone (never on Drive or the web).
 * The stream key is encrypted with an Android Keystore key.
 */
@Singleton
class StreamSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.streamDataStore

    suspend fun load(): StreamSettings {
        val prefs = dataStore.data.first()
        return StreamSettings(
            rtmpUrl = prefs[RTMP_URL].orEmpty(),
            streamKey = prefs[STREAM_KEY]?.let { runCatching { KeystoreCipher.decrypt(it) }.getOrNull() }.orEmpty(),
            playbackUrl = prefs[PLAYBACK_URL].orEmpty(),
            pageUrl = prefs[PAGE_URL].orEmpty(),
            recordingUrl = prefs[RECORDING_URL].orEmpty(),
        )
    }

    suspend fun save(settings: StreamSettings) {
        dataStore.edit { prefs ->
            prefs[RTMP_URL] = settings.rtmpUrl.trim()
            if (settings.streamKey.isBlank()) prefs.remove(STREAM_KEY) else prefs[STREAM_KEY] = KeystoreCipher.encrypt(settings.streamKey.trim())
            prefs[PLAYBACK_URL] = settings.playbackUrl.trim()
            prefs[PAGE_URL] = settings.pageUrl.trim()
            prefs[RECORDING_URL] = settings.recordingUrl.trim()
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val RTMP_URL = stringPreferencesKey("rtmp_url")
        val STREAM_KEY = stringPreferencesKey("stream_key_enc")
        val PLAYBACK_URL = stringPreferencesKey("playback_url")
        val PAGE_URL = stringPreferencesKey("page_url")
        val RECORDING_URL = stringPreferencesKey("recording_url")
    }
}

/** AES-GCM with a non-exportable Android Keystore key. */
private object KeystoreCipher {
    private const val ALIAS = "soslive_stream_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain.toByteArray())
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }
}
