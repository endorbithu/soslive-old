package info.soslive.stream.core.config

import info.soslive.stream.BuildConfig

/** Build-time configuration (see app/build.gradle.kts). There is no SOSlive backend. */
object AppConfig {
    /** Web app that shows events; the event link is `<webappUrl>/e/{fileId}`. */
    val webappUrl: String = BuildConfig.WEBAPP_URL
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    /** Without a Google client id the app stores its "Drive" files locally (development only). */
    val googleConfigured: Boolean get() = googleWebClientId.isNotBlank()

    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    /** A photo incident stays open this long, so more photos / messages go to the same event. */
    const val ACTIVE_EVENT_WINDOW_MILLIS: Long = 2 * 60 * 60 * 1000L

    /** Position entries at most this often (per the event format guide). */
    const val POSITION_INTERVAL_MILLIS: Long = 30_000L
    const val STREAM_MAX_RETRIES: Int = 3
    const val STREAM_RETRY_DELAY_MILLIS: Long = 3_000L
}
