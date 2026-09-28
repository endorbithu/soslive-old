package info.soslive.stream.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import info.soslive.stream.R
import info.soslive.stream.auth.sso.SsoProvider
import info.soslive.stream.auth.sso.SsoResult
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.data.remote.toUiText
import info.soslive.stream.domain.model.User
import info.soslive.stream.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val loading: Boolean = false,
    val emailError: UiText? = null,
    val passwordError: UiText? = null,
    val displayNameError: UiText? = null,
    val error: UiText? = null,
)

/**
 * Shared by the login and register screens. A successful login only stores the session - the app's
 * root observes [AuthRepository.currentUser] and switches to the main screens by itself.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, emailError = null, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, passwordError = null, error = null) }
    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value, displayNameError = null, error = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun login() {
        val s = _state.value
        val emailError = if (s.email.isBlank()) UiText.Res(R.string.error_email_required) else null
        val passwordError = if (s.password.isEmpty()) UiText.Res(R.string.error_password_required) else null
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        submit { authRepository.login(s.email, s.password) }
    }

    fun register() {
        val s = _state.value
        val nameError = if (s.displayName.isBlank()) UiText.Res(R.string.error_name_required) else null
        val emailError = if (!EMAIL.matches(s.email.trim())) UiText.Res(R.string.error_email_invalid) else null
        val passwordError = if (s.password.length < MIN_PASSWORD) UiText.Res(R.string.error_password_short, MIN_PASSWORD) else null
        if (nameError != null || emailError != null || passwordError != null) {
            _state.update { it.copy(displayNameError = nameError, emailError = emailError, passwordError = passwordError) }
            return
        }
        submit { authRepository.register(s.email, s.password, s.displayName) }
    }

    fun onSsoResult(provider: SsoProvider, result: SsoResult) {
        when (result) {
            is SsoResult.Success -> submit {
                when (provider) {
                    SsoProvider.GOOGLE -> authRepository.loginWithGoogle(result.token)
                    SsoProvider.FACEBOOK -> authRepository.loginWithFacebook(result.token)
                }
            }
            is SsoResult.Failure -> _state.update { it.copy(error = UiText.Res(R.string.error_sso_failed, result.message)) }
            SsoResult.Cancelled -> Unit
        }
    }

    private fun submit(call: suspend () -> Result<User>) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = call()
            _state.update {
                it.copy(loading = false, error = result.exceptionOrNull()?.toUiText(), password = if (result.isSuccess) "" else it.password)
            }
        }
    }

    companion object {
        const val MIN_PASSWORD = 8
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
