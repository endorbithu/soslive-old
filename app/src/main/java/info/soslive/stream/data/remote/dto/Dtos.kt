package info.soslive.stream.data.remote.dto

import kotlinx.serialization.Serializable

// Wire format of the SOSlive API - see mock-server/openapi.yaml.

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class GoogleLoginRequest(val idToken: String)

@Serializable
data class FacebookLoginRequest(val accessToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String?)

@Serializable
data class SessionDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long = 0,
    val user: UserDto,
)

@Serializable
data class UserDto(
    val id: Long,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val providers: List<String> = emptyList(),
    val sosContacts: List<String> = emptyList(),
    val sosMessage: String = "",
)

@Serializable
data class UpdateMeRequest(
    val displayName: String? = null,
    val sosContacts: List<String>? = null,
    val sosMessage: String? = null,
)

@Serializable
data class CreateEventRequest(val type: String, val lat: Double? = null, val lng: Double? = null)

@Serializable
data class LocationUpdateRequest(val lat: Double, val lng: Double, val accuracy: Float? = null)

@Serializable
data class LocationDto(val lat: Double, val lng: Double, val at: String)

@Serializable
data class StreamTargetDto(val url: String, val streamKey: String, val publishUrl: String)

@Serializable
data class EventDto(
    val id: Long,
    val type: String,
    val status: String,
    val createdAt: String,
    val stoppedAt: String? = null,
    val lastLocation: LocationDto? = null,
    val shareUrl: String,
    val stream: StreamTargetDto? = null,
    val commentCount: Int = 0,
    val photoCount: Int = 0,
)

@Serializable
data class EventListDto(val items: List<EventDto>)

@Serializable
data class CommentDto(
    val id: Long,
    val eventId: Long,
    val authorName: String,
    val authorType: String,
    val message: String,
    val createdAt: String,
)

@Serializable
data class CommentListDto(val total: Int, val items: List<CommentDto>)

@Serializable
data class AddCommentRequest(val message: String)

@Serializable
data class PhotoDto(val id: Long, val eventId: Long, val url: String, val createdAt: String)

@Serializable
data class PhotoListDto(val items: List<PhotoDto>)

@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String)
