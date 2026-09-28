package info.soslive.stream.core.config

import info.soslive.stream.BuildConfig

/** Build-time configuration (see app/build.gradle.kts). */
object AppConfig {
    val apiBaseUrl: String = BuildConfig.API_BASE_URL
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID
    val facebookAppId: String = BuildConfig.FACEBOOK_APP_ID
    val facebookClientToken: String = BuildConfig.FACEBOOK_CLIENT_TOKEN

    /** Without a Google Web client id, Google sign-in is simulated (mock token to the backend). */
    val googleConfigured: Boolean get() = googleWebClientId.isNotBlank()

    /** Without a Facebook app id + client token, Facebook sign-in is simulated. */
    val facebookConfigured: Boolean get() = facebookAppId.isNotBlank() && facebookClientToken.isNotBlank()

    /** A photo incident stays "open" this long, so more photos / comments go to the same event (legacy: 2 hours). */
    const val ACTIVE_EVENT_WINDOW_MILLIS: Long = 2 * 60 * 60 * 1000L

    const val COMMENT_POLL_INTERVAL_MILLIS: Long = 10_000L
    const val LOCATION_UPDATE_INTERVAL_MILLIS: Long = 30_000L
    const val STREAM_MAX_RETRIES: Int = 3
    const val STREAM_RETRY_DELAY_MILLIS: Long = 3_000L
}
