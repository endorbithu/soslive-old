package info.soslive.stream.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import info.soslive.stream.R
import info.soslive.stream.auth.AccountStore
import info.soslive.stream.auth.GoogleAuth
import info.soslive.stream.auth.UserAccount
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.core.ui.toUiText
import info.soslive.stream.drive.ContactRules
import info.soslive.stream.drive.SosConfig
import info.soslive.stream.drive.SosliveDrive
import info.soslive.stream.stream.StreamSettings
import info.soslive.stream.stream.StreamSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val account: UserAccount? = null,
    val loading: Boolean = true,
    val emails: String = "",
    val phones: String = "",
    val maxEvents: String = SosConfig.DEFAULT_MAX_EVENTS.toString(),
    val emailsError: UiText? = null,
    val phonesError: UiText? = null,
    val maxEventsError: UiText? = null,
    val saving: Boolean = false,
    val message: UiText? = null,
    /** The user's own streaming service - stored only on this phone. */
    val stream: StreamSettings = StreamSettings(),
    val streamErrors: Set<StreamSettings.Field> = emptySet(),
)

/** Edits config.json on Drive - the web only shows it ("can only be changed in the mobile app"). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountStore: AccountStore,
    private val drive: SosliveDrive,
    private val googleAuth: GoogleAuth,
    private val streamStore: StreamSettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Last loaded config - keeps unknown fields when saving. */
    private var base = SosConfig()

    init {
        viewModelScope.launch {
            _state.update { it.copy(account = accountStore.currentAccount(), stream = streamStore.load()) }
            drive.cachedConfig()?.let(::fill)
            try {
                drive.readRemoteConfig()?.let(::fill)
            } catch (e: Exception) {
                _state.update { it.copy(message = e.toUiText()) }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    private fun fill(config: SosConfig) {
        base = config
        _state.update {
            it.copy(
                emails = config.notificationEmails.joinToString("\n"),
                phones = config.notificationPhones.joinToString("\n"),
                maxEvents = config.maxEvents.toString(),
            )
        }
    }

    fun onEmailsChange(value: String) = _state.update { it.copy(emails = value, emailsError = null, message = null) }
    fun onPhonesChange(value: String) = _state.update { it.copy(phones = value, phonesError = null, message = null) }
    fun onMaxEventsChange(value: String) = _state.update { it.copy(maxEvents = value.filter(Char::isDigit).take(5), maxEventsError = null, message = null) }
    fun messageShown() = _state.update { it.copy(message = null) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val emails = ContactRules.split(s.emails)
        val phones = ContactRules.split(s.phones)
        val badEmails = emails.filterNot(ContactRules::isValidEmail)
        val badPhones = phones.filterNot(ContactRules::isValidPhone)
        val maxEvents = s.maxEvents.toIntOrNull()
        _state.update {
            it.copy(
                emailsError = badEmails.takeIf { b -> b.isNotEmpty() }?.let { b -> UiText.Res(R.string.error_emails_invalid, b.joinToString()) },
                phonesError = badPhones.takeIf { b -> b.isNotEmpty() }?.let { b -> UiText.Res(R.string.error_phones_invalid, b.joinToString()) },
                maxEventsError = if (maxEvents == null || maxEvents < 1) UiText.Res(R.string.error_max_events) else null,
            )
        }
        if (badEmails.isNotEmpty() || badPhones.isNotEmpty() || maxEvents == null || maxEvents < 1) return

        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val config = base.copy(notificationEmails = emails, notificationPhones = phones, maxEvents = maxEvents)
            try {
                drive.saveConfig(config)
                fill(config)
                _state.update { it.copy(message = UiText.Res(R.string.settings_saved)) }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.toUiText()) }
            }
            _state.update { it.copy(saving = false) }
        }
    }

    fun onStreamChange(value: StreamSettings) = _state.update { it.copy(stream = value, streamErrors = emptySet(), message = null) }

    /** Saved on the phone only (the key encrypted with the Android Keystore) - never to Drive. */
    fun saveStream() {
        val stream = _state.value.stream
        val errors = stream.validate().toSet()
        _state.update { it.copy(streamErrors = errors) }
        if (errors.isNotEmpty()) return
        viewModelScope.launch {
            try {
                streamStore.save(stream)
                _state.update { it.copy(message = UiText.Res(R.string.stream_settings_saved)) }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.toUiText()) }
            }
        }
    }

    /** Local data only (including the stream settings); the files on Drive stay. */
    fun signOut() {
        viewModelScope.launch {
            streamStore.clear()
            googleAuth.signOut()
            accountStore.setAccount(null)
        }
    }
}
