package info.soslive.stream.data.remote

import info.soslive.stream.data.local.SessionStorage
import info.soslive.stream.data.local.toStored
import info.soslive.stream.data.remote.dto.RefreshRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val AUTHORIZATION = "Authorization"

private fun Request.withBearer(token: String) = newBuilder().header(AUTHORIZATION, "Bearer $token").build()

/** Adds the current access token to every request. (OkHttp calls this on a background thread.) */
@Singleton
class AuthInterceptor @Inject constructor(
    private val sessionStorage: SessionStorage,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { sessionStorage.current()?.accessToken }
        val request = if (token != null) chain.request().withBearer(token) else chain.request()
        return chain.proceed(request)
    }
}

/**
 * On 401, exchanges the refresh token for a new session once and retries the request.
 * If the refresh token is rejected the session is cleared - the UI observes that and shows login.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val sessionStorage: SessionStorage,
    private val authApi: AuthApi,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponseCount() >= 1) return null
        val failedToken = response.request.header(AUTHORIZATION)?.removePrefix("Bearer ")

        synchronized(lock) {
            val current = runBlocking { sessionStorage.current() } ?: return null
            // Another request refreshed the token while we were waiting.
            if (failedToken != null && current.accessToken != failedToken) {
                return response.request.withBearer(current.accessToken)
            }

            val refreshed = try {
                authApi.refresh(RefreshRequest(current.refreshToken)).execute()
            } catch (_: IOException) {
                return null
            }
            val body = refreshed.body()
            if (!refreshed.isSuccessful || body == null) {
                if (refreshed.code() == 401) runBlocking { sessionStorage.clear() }
                return null
            }
            runBlocking { sessionStorage.save(body.toStored()) }
            return response.request.withBearer(body.accessToken)
        }
    }

    private fun Response.priorResponseCount(): Int {
        var count = 0
        var prior = priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
