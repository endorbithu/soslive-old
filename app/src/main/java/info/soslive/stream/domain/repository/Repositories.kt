package info.soslive.stream.domain.repository

import info.soslive.stream.domain.model.ActiveEvent
import info.soslive.stream.domain.model.Comment
import info.soslive.stream.domain.model.CommentPage
import info.soslive.stream.domain.model.Event
import info.soslive.stream.domain.model.EventType
import info.soslive.stream.domain.model.GeoPoint
import info.soslive.stream.domain.model.Photo
import info.soslive.stream.domain.model.User
import kotlinx.coroutines.flow.Flow
import java.io.File

interface AuthRepository {
    /** The logged in user, or null when logged out (also after the refresh token was rejected). */
    val currentUser: Flow<User?>

    suspend fun login(email: String, password: String): Result<User>
    suspend fun register(email: String, password: String, displayName: String): Result<User>
    suspend fun loginWithGoogle(idToken: String): Result<User>
    suspend fun loginWithFacebook(accessToken: String): Result<User>
    suspend fun logout()
}

interface ProfileRepository {
    suspend fun refresh(): Result<User>
    suspend fun update(displayName: String, sosContacts: List<String>, sosMessage: String): Result<User>
}

interface EventRepository {
    /** The currently open incident (null when none or expired). */
    val activeEvent: Flow<ActiveEvent?>

    /** Creates the event on the backend and makes it the active one. */
    suspend fun createEvent(type: EventType, location: GeoPoint?): Result<Event>
    suspend fun clearActiveEvent()

    suspend fun myEvents(): Result<List<Event>>
    suspend fun event(id: Long): Result<Event>
    suspend fun sendLocation(id: Long, location: GeoPoint): Result<Unit>
    suspend fun stop(id: Long): Result<Event>

    suspend fun comments(id: Long, sinceId: Long? = null): Result<CommentPage>
    suspend fun addComment(id: Long, message: String): Result<Comment>

    suspend fun photos(id: Long): Result<List<Photo>>
    suspend fun uploadPhoto(id: Long, file: File): Result<Photo>
}
