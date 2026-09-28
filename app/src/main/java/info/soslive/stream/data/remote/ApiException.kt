package info.soslive.stream.data.remote

import info.soslive.stream.R
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.data.remote.dto.ErrorEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/** Backend error: {"error":{"code","message"}} - or a local network / parse problem. */
class ApiException(
    val httpStatus: Int?,
    val code: String,
    override val message: String,
) : Exception(message) {
    companion object {
        const val NETWORK_ERROR = "network_error"
        const val PARSE_ERROR = "parse_error"
    }
}

/** Runs a Retrofit call and maps every failure to [ApiException]. */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    Result.failure(e.toApiException(json))
} catch (e: IOException) {
    Result.failure(ApiException(null, ApiException.NETWORK_ERROR, e.message ?: "Network error"))
} catch (e: SerializationException) {
    Result.failure(ApiException(null, ApiException.PARSE_ERROR, e.message ?: "Unexpected response"))
}

private fun HttpException.toApiException(json: Json): ApiException {
    val raw = response()?.errorBody()?.string()
    val body = raw?.let { runCatching { json.decodeFromString<ErrorEnvelope>(it).error }.getOrNull() }
    return ApiException(code(), body?.code ?: "http_${code()}", body?.message ?: message())
}

/** User facing text for a failure. Known error codes are localized, the rest shows the server message. */
fun Throwable.toUiText(): UiText {
    if (this !is ApiException) return UiText.Res(R.string.error_generic)
    return when (code) {
        ApiException.NETWORK_ERROR -> UiText.Res(R.string.error_network)
        "invalid_credentials" -> UiText.Res(R.string.error_invalid_credentials)
        "too_many_attempts" -> UiText.Res(R.string.error_too_many_attempts)
        "email_taken" -> UiText.Res(R.string.error_email_taken)
        "invalid_sso_token" -> UiText.Res(R.string.error_sso_rejected)
        else -> UiText.Raw(message)
    }
}
