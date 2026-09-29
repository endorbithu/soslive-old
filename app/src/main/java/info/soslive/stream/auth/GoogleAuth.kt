package info.soslive.stream.auth

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import info.soslive.stream.core.config.AppConfig
import info.soslive.stream.drive.AccessTokenProvider
import info.soslive.stream.drive.DriveConsentRequiredException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SignInResult {
    data class Success(val account: UserAccount) : SignInResult
    data object Cancelled : SignInResult
    data class Failure(val message: String) : SignInResult
}

sealed interface DriveAuthorization {
    data class Granted(val accessToken: String) : DriveAuthorization
    /** The user has to approve drive.file on Google's consent screen (launch this intent). */
    data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuthorization
    data class Failure(val message: String) : DriveAuthorization
}

/**
 * "Sign in with Google" (Credential Manager) plus the drive.file authorization
 * (Identity AuthorizationClient). The token stays on the device - nothing is sent to a backend.
 */
@Singleton
class GoogleAuth @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccessTokenProvider {

    private val authorizationClient = Identity.getAuthorizationClient(context)
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var cachedAt = 0L

    private fun request(email: String?): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(AppConfig.DRIVE_FILE_SCOPE)))
        .apply { if (email != null) setAccount(Account(email, "com.google")) }
        .build()

    /** Account picker + Google ID token (identity only). */
    suspend fun signIn(activity: Activity): SignInResult {
        val option = GetSignInWithGoogleOption.Builder(AppConfig.googleWebClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                SignInResult.Success(
                    UserAccount(email = google.id, name = google.displayName ?: google.givenName ?: google.id, simulated = false),
                )
            } else {
                SignInResult.Failure("Unexpected credential type: ${credential.type}")
            }
        } catch (_: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: GetCredentialException) {
            SignInResult.Failure(e.message ?: e.type)
        }
    }

    /** Asks for drive.file. Silent when already granted, otherwise returns [DriveAuthorization.NeedsConsent]. */
    suspend fun authorizeDrive(email: String?): DriveAuthorization = try {
        val result = authorizationClient.authorize(request(email)).await()
        val pendingIntent = result.pendingIntent
        when {
            result.hasResolution() && pendingIntent != null -> DriveAuthorization.NeedsConsent(pendingIntent)
            result.accessToken != null -> DriveAuthorization.Granted(remember(result.accessToken!!))
            else -> DriveAuthorization.Failure("No access token")
        }
    } catch (e: ApiException) {
        DriveAuthorization.Failure(e.message ?: "Authorization failed (${e.statusCode})")
    }

    /** Result of the consent screen launched for [DriveAuthorization.NeedsConsent]. */
    fun consentResult(data: Intent?): DriveAuthorization = try {
        val result = authorizationClient.getAuthorizationResultFromIntent(data)
        result.accessToken?.let { DriveAuthorization.Granted(remember(it)) } ?: DriveAuthorization.Failure("Permission not granted")
    } catch (e: ApiException) {
        DriveAuthorization.Failure(e.message ?: "Permission not granted")
    }

    private fun remember(token: String): String {
        cachedToken = token
        cachedAt = System.currentTimeMillis()
        return token
    }

    @Volatile
    var accountEmail: String? = null

    /** Access token for Drive calls; cached ~45 min, silently re-authorized when stale. */
    override suspend fun accessToken(forceRefresh: Boolean): String = mutex.withLock {
        val cached = cachedToken
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cachedAt < TOKEN_TTL_MILLIS) return cached
        when (val auth = authorizeDrive(accountEmail)) {
            is DriveAuthorization.Granted -> auth.accessToken
            is DriveAuthorization.NeedsConsent -> throw DriveConsentRequiredException()
            is DriveAuthorization.Failure -> throw java.io.IOException(auth.message)
        }
    }

    suspend fun signOut() {
        cachedToken = null
        accountEmail = null
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    private companion object {
        const val TOKEN_TTL_MILLIS = 45 * 60 * 1000L
    }
}
