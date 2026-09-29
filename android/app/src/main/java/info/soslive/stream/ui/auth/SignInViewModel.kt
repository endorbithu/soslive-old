package info.soslive.stream.ui.auth

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import info.soslive.stream.R
import info.soslive.stream.auth.AccountStore
import info.soslive.stream.auth.DriveAuthorization
import info.soslive.stream.auth.GoogleAuth
import info.soslive.stream.auth.SignInResult
import info.soslive.stream.auth.UserAccount
import info.soslive.stream.core.config.AppConfig
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.core.ui.toUiText
import info.soslive.stream.drive.LegacySettings
import info.soslive.stream.drive.SosliveDrive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val busy: Boolean = false,
    val error: UiText? = null,
    /** Google's consent screen for drive.file, to be launched by the UI. */
    val consent: PendingIntent? = null,
    /** The user refused Drive access - explain why it is needed. */
    val driveDenied: Boolean = false,
)

/**
 * Sign in with Google, then authorize drive.file. The account is stored only after Drive access
 * was granted and the SOSlive folder is ready - without it the app cannot record events.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val googleAuth: GoogleAuth,
    private val accountStore: AccountStore,
    private val drive: SosliveDrive,
    private val legacy: LegacySettings,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    val simulated: Boolean get() = !AppConfig.googleConfigured

    private var pendingAccount: UserAccount? = null

    fun signIn(activity: Activity) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, driveDenied = false) }
        viewModelScope.launch {
            when (val result = googleAuth.signIn(activity)) {
                is SignInResult.Success -> {
                    pendingAccount = result.account
                    googleAuth.accountEmail = result.account.email
                    handle(googleAuth.authorizeDrive(result.account.email))
                }
                SignInResult.Cancelled -> _state.update { it.copy(busy = false) }
                is SignInResult.Failure -> _state.update {
                    it.copy(busy = false, error = UiText.Res(R.string.error_sign_in, result.message))
                }
            }
        }
    }

    /** Result of the Drive consent screen. */
    fun onConsentResult(resultOk: Boolean, data: Intent?) {
        _state.update { it.copy(consent = null) }
        if (!resultOk) {
            _state.update { it.copy(busy = false, driveDenied = true) }
            return
        }
        viewModelScope.launch { handle(googleAuth.consentResult(data)) }
    }

    fun retryDrive() {
        val account = pendingAccount ?: return
        _state.update { it.copy(busy = true, driveDenied = false, error = null) }
        viewModelScope.launch { handle(googleAuth.authorizeDrive(account.email)) }
    }

    private suspend fun handle(authorization: DriveAuthorization) {
        when (authorization) {
            is DriveAuthorization.Granted -> finish(pendingAccount ?: return)
            is DriveAuthorization.NeedsConsent -> _state.update { it.copy(consent = authorization.pendingIntent) }
            is DriveAuthorization.Failure -> _state.update {
                it.copy(busy = false, driveDenied = true, error = UiText.Raw(authorization.message))
            }
        }
    }

    /** Development mode without a Google project: files are kept on the device. */
    fun signInSimulated(email: String, name: String) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { finish(UserAccount(email.trim(), name.trim().ifEmpty { email.substringBefore('@') }, simulated = true)) }
    }

    /** Prepares the SOSlive folder, migrates legacy settings once, then stores the account. */
    private suspend fun finish(account: UserAccount) {
        try {
            drive.forgetLocalState()
            drive.folderId()
            val remote = drive.readRemoteConfig()
            if (remote == null) legacy.toConfig()?.let { drive.saveConfig(it) }
            accountStore.setAccount(account)
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.toUiText()) }
            return
        }
        _state.update { it.copy(busy = false) }
    }
}
