package info.soslive.stream.stream

import info.soslive.stream.core.config.AppConfig
import java.security.SecureRandom

/** Where the app publishes (RTMP) and where viewers play the stream (goes into the event's "stream" field). */
data class StreamSession(
    val publishUrl: String,
    /** HLS (.m3u8) playback URL; empty when the provider does not know it yet. */
    val playbackUrl: String,
)

/**
 * Source of stream targets. Replaceable: a hosted provider (Mux, Cloudflare Stream, ...) can
 * implement this later - its API key must then live on a server, not in the app.
 */
fun interface StreamProvider {
    suspend fun createStream(): StreamSession
}

/**
 * Stream targets from build configuration: a random, unguessable stream key per event,
 * `<STREAM_RTMP_URL>/<key>` to publish and `STREAM_HLS_TEMPLATE` with `{key}` to play.
 * Works out of the box with MediaMTX / SRS / nginx-rtmp.
 */
class TemplateStreamProvider(
    private val rtmpUrl: String = AppConfig.streamRtmpUrl,
    private val hlsTemplate: String = AppConfig.streamHlsTemplate,
    private val random: SecureRandom = SecureRandom(),
) : StreamProvider {

    override suspend fun createStream(): StreamSession {
        val key = newKey()
        return StreamSession(
            publishUrl = "${rtmpUrl.trimEnd('/')}/$key",
            playbackUrl = hlsTemplate.replace("{key}", key),
        )
    }

    /** 128 bit, lowercase hex. */
    fun newKey(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
