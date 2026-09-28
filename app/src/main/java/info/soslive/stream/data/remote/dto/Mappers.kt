package info.soslive.stream.data.remote.dto

import info.soslive.stream.domain.model.Comment
import info.soslive.stream.domain.model.CommentPage
import info.soslive.stream.domain.model.Event
import info.soslive.stream.domain.model.EventStatus
import info.soslive.stream.domain.model.EventType
import info.soslive.stream.domain.model.GeoPoint
import info.soslive.stream.domain.model.Photo
import info.soslive.stream.domain.model.StreamTarget
import info.soslive.stream.domain.model.User
import java.time.Instant

private fun parseInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun UserDto.toDomain() = User(
    id = id,
    email = email,
    displayName = displayName,
    avatarUrl = avatarUrl,
    providers = providers,
    sosContacts = sosContacts,
    sosMessage = sosMessage,
)

fun EventDto.toDomain() = Event(
    id = id,
    type = runCatching { EventType.valueOf(type) }.getOrDefault(EventType.LIVE),
    status = runCatching { EventStatus.valueOf(status) }.getOrDefault(EventStatus.UNKNOWN),
    createdAt = parseInstant(createdAt) ?: Instant.EPOCH,
    stoppedAt = parseInstant(stoppedAt),
    lastLocation = lastLocation?.let { GeoPoint(it.lat, it.lng) },
    shareUrl = shareUrl,
    stream = stream?.let { StreamTarget(it.url, it.streamKey, it.publishUrl) },
    commentCount = commentCount,
    photoCount = photoCount,
)

fun CommentDto.toDomain() = Comment(
    id = id,
    eventId = eventId,
    authorName = authorName,
    fromOwner = authorType == "OWNER",
    message = message,
    createdAt = parseInstant(createdAt) ?: Instant.EPOCH,
)

fun CommentListDto.toDomain() = CommentPage(total, items.map { it.toDomain() })

fun PhotoDto.toDomain() = Photo(id, eventId, url, parseInstant(createdAt) ?: Instant.EPOCH)
