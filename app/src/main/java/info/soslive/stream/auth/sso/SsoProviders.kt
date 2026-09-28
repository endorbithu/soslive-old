package info.soslive.stream.auth.sso

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import info.soslive.stream.core.config.AppConfig

enum class SsoProvider { GOOGLE, FACEBOOK }

sealed interface SsoResult {
    /** Token for the backend: Google ID token / Facebook access token / "mock:..." when simulated. */
    data class Success(val token: String) : SsoResult
    data object Cancelled : SsoResult
    data class Failure(val message: String) : SsoResult
}

object SimulatedSso {
    /** Token format the mock backend accepts when a provider is not configured. */
    fun token(email: String, displayName: String): String =
        "mock:${email.trim()}" + displayName.trim().takeIf { it.isNotEmpty() }?.let { "|$it" }.orEmpty()

    fun isSimulated(provider: SsoProvider): Boolean = when (provider) {
        SsoProvider.GOOGLE -> !AppConfig.googleConfigured
        SsoProvider.FACEBOOK -> !AppConfig.facebookConfigured
    }
}

/** "Sign in with Google" through Credential Manager - returns a Google ID token. */
object GoogleSignIn {
    private const val TAG = "GoogleSignIn"

    suspend fun requestIdToken(activity: Activity): SsoResult {
        val option = GetSignInWithGoogleOption.Builder(AppConfig.googleWebClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                SsoResult.Success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                SsoResult.Failure("Unexpected credential type: ${credential.type}")
            }
        } catch (_: GetCredentialCancellationException) {
            SsoResult.Cancelled
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Google sign-in failed", e)
            SsoResult.Failure(e.message ?: e.type)
        }
    }
}

/**
 * Facebook Login with the Activity Result API. Returns a launcher; the result (user access token)
 * is delivered to [onResult]. Only call the launcher when [AppConfig.facebookConfigured].
 */
@Composable
fun rememberFacebookLogin(onResult: (SsoResult) -> Unit): () -> Unit {
    val currentOnResult = rememberUpdatedState(onResult)
    val callbackManager = remember { CallbackManager.Factory.create() }
    val loginManager = remember { LoginManager.getInstance() }
    val launcher = rememberLauncherForActivityResult(loginManager.createLogInActivityResultContract(callbackManager, null)) { }

    DisposableEffect(callbackManager) {
        loginManager.registerCallback(
            callbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) = currentOnResult.value(SsoResult.Success(result.accessToken.token))
                override fun onCancel() = currentOnResult.value(SsoResult.Cancelled)
                override fun onError(error: FacebookException) = currentOnResult.value(SsoResult.Failure(error.message ?: "Facebook login failed"))
            },
        )
        onDispose { loginManager.unregisterCallback(callbackManager) }
    }
    return { launcher.launch(listOf("email", "public_profile")) }
}
