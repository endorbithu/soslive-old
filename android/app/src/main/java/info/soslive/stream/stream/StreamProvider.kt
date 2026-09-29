package info.soslive.stream.stream

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The user's own streaming service (YouTube Live, Facebook Live, Twitch, Cloudflare Stream,
 * a self-hosted MediaMTX, ...). Entered in the app's settings and kept only on the phone.
 */
data class StreamSettings(
    /** RTMP(S) ingest URL, e.g. rtmp://a.rtmp.youtube.com/live2 */
    val rtmpUrl: String = "",
    /** Stream key (secret); appended to [rtmpUrl]. Empty when the URL already contains it. */
    val streamKey: String = "",
    /** Directly playable URL (HLS .m3u8 / .mp4) -> event "stream" field. Optional. */
    val playbackUrl: String = "",
    /** Viewer page (e.g. the YouTube / Twitch channel page) -> event "stream_page". Optional. */
    val pageUrl: String = "",
    /** Where the recording can be downloaded / watched later -> event "recording". Optional. */
    val recordingUrl: String = "",
) {
    val isConfigured: Boolean get() = rtmpUrl.isNotBlank()

    /** Full publish URL: `<rtmpUrl>/<streamKey>`. */
    val publishUrl: String
        get() = if (streamKey.isBlank()) rtmpUrl.trim() else rtmpUrl.trim().trimEnd('/') + "/" + streamKey.trim()

    fun validate(): List<Field> = buildList {
        if (rtmpUrl.isNotBlank() && !RTMP.matches(rtmpUrl.trim())) add(Field.RTMP_URL)
        if (rtmpUrl.isBlank() && (streamKey.isNotBlank() || playbackUrl.isNotBlank() || pageUrl.isNotBlank() || recordingUrl.isNotBlank())) {
            add(Field.RTMP_URL)
        }
        if (!isHttpOrEmpty(playbackUrl)) add(Field.PLAYBACK_URL)
        if (!isHttpOrEmpty(pageUrl)) add(Field.PAGE_URL)
        if (!isHttpOrEmpty(recordingUrl.replace(START_PLACEHOLDER, "x"))) add(Field.RECORDING_URL)
    }

    enum class Field { RTMP_URL, PLAYBACK_URL, PAGE_URL, RECORDING_URL }

    companion object {
        /** Optional placeholder in [recordingUrl]: the event start time (ISO 8601 UTC), e.g. for MediaMTX /get. */
        const val START_PLACEHOLDER = "{start}"
        private val RTMP = Regex("^rtmps?://\\S+$", RegexOption.IGNORE_CASE)
        private val HTTP = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
        private fun isHttpOrEmpty(value: String) = value.isBlank() || HTTP.matches(value.trim())
    }
}

/** Where the app publishes and which URLs go into the event file. */
data class StreamSession(
    val publishUrl: String,
    /** -> "stream" (directly playable), may be empty */
    val playbackUrl: String,
    /** -> "stream_page", may be empty */
    val pageUrl: String,
    /** -> "recording", may be empty */
    val recordingUrl: String,
)

/** Source of the stream target; null when the user has not set up streaming. */
fun interface StreamProvider {
    suspend fun createStream(start: Instant): StreamSession?
}

/** Uses the user's own streaming settings as they are - no SOSlive server in between. */
class UserStreamProvider(private val settings: suspend () -> StreamSettings) : StreamProvider {
    override suspend fun createStream(start: Instant): StreamSession? {
        val s = settings().takeIf { it.isConfigured } ?: return null
        return StreamSession(
            publishUrl = s.publishUrl,
            playbackUrl = s.playbackUrl.trim(),
            pageUrl = s.pageUrl.trim(),
            recordingUrl = s.recordingUrl.trim()
                .replace(StreamSettings.START_PLACEHOLDER, start.truncatedTo(ChronoUnit.SECONDS).toString()),
        )
    }
}
