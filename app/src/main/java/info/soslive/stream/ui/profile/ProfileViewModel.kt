package info.soslive.stream.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import info.soslive.stream.R
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.data.remote.toUiText
import info.soslive.stream.domain.SosContacts
import info.soslive.stream.domain.model.User
import info.soslive.stream.domain.repository.AuthRepository
import info.soslive.stream.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val user: User? = null,
    val displayName: String = "",
    val contacts: String = "",
    val sosMessage: String = "",
    val contactsError: UiText? = null,
    val saving: Boolean = false,
    val message: UiText? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.currentUser.first()?.let(::fill)
            // Pick up changes made elsewhere (e.g. the web) - silently ignore failures.
            profileRepository.refresh().onSuccess(::fill)
        }
    }

    private fun fill(user: User) = _state.update {
        it.copy(
            user = user,
            displayName = user.displayName,
            contacts = SosContacts.format(user.sosContacts),
            sosMessage = user.sosMessage,
        )
    }

    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value.take(80), message = null) }
    fun onContactsChange(value: String) = _state.update { it.copy(contacts = value, contactsError = null, message = null) }
    fun onSosMessageChange(value: String) = _state.update { it.copy(sosMessage = value.take(300), message = null) }
    fun messageShown() = _state.update { it.copy(message = null) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val numbers = when (val parsed = SosContacts.parse(s.contacts)) {
            is SosContacts.ParseResult.Valid -> parsed.numbers
            is SosContacts.ParseResult.Invalid -> {
                _state.update { it.copy(contactsError = UiText.Res(R.string.error_phone_invalid, parsed.numbers.joinToString())) }
                return
            }
            SosContacts.ParseResult.TooMany -> {
                _state.update { it.copy(contactsError = UiText.Res(R.string.error_phone_too_many, SosContacts.MAX_CONTACTS)) }
                return
            }
        }
        if (s.displayName.isBlank()) {
            _state.update { it.copy(message = UiText.Res(R.string.error_name_required)) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            profileRepository.update(s.displayName, numbers, s.sosMessage)
                .onSuccess { user ->
                    fill(user)
                    _state.update { it.copy(message = UiText.Res(R.string.profile_saved)) }
                }
                .onFailure { error -> _state.update { it.copy(message = error.toUiText()) } }
            _state.update { it.copy(saving = false) }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
