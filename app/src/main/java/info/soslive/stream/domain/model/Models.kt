package info.soslive.stream.domain.model

import java.time.Instant

data class User(
    val id: Long,
    val email: String,
    val displayName: String,
    val avatarUrl: String?,
    val providers: List<String>,
    val sosContacts: List<String>,
    val sosMessage: String,
)

enum class EventType { SOS, LIVE, PHOTO }

enum class EventStatus { LIVE, OPEN, STOPPED, UNKNOWN }

data class GeoPoint(val lat: Double, val lng: Double, val accuracy: Float? = null)

data class StreamTarget(val url: String, val streamKey: String, val publishUrl: String)

data class Event(
    val id: Long,
    val type: EventType,
    val status: EventStatus,
    val createdAt: Instant,
    val stoppedAt: Instant?,
    val lastLocation: GeoPoint?,
    val shareUrl: String,
    val stream: StreamTarget?,
    val commentCount: Int,
    val photoCount: Int,
)

data class Comment(
    val id: Long,
    val eventId: Long,
    val authorName: String,
    val fromOwner: Boolean,
    val message: String,
    val createdAt: Instant,
)

data class CommentPage(val total: Int, val items: List<Comment>)

data class Photo(val id: Long, val eventId: Long, val url: String, val createdAt: Instant)

/**
 * The incident the user is currently working on. Photos and comments go to it until it is
 * stopped or [info.soslive.stream.core.config.AppConfig.ACTIVE_EVENT_WINDOW_MILLIS] passes.
 */
data class ActiveEvent(
    val id: Long,
    val type: EventType,
    val startedAtMillis: Long,
    val shareUrl: String,
) {
    fun isExpired(nowMillis: Long, windowMillis: Long): Boolean = nowMillis - startedAtMillis >= windowMillis
}
