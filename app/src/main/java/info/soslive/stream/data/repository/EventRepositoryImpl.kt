package info.soslive.stream.data.repository

import info.soslive.stream.core.config.AppConfig
import info.soslive.stream.data.local.ActiveEventStorage
import info.soslive.stream.data.remote.SosLiveApi
import info.soslive.stream.data.remote.apiCall
import info.soslive.stream.data.remote.dto.AddCommentRequest
import info.soslive.stream.data.remote.dto.CreateEventRequest
import info.soslive.stream.data.remote.dto.LocationUpdateRequest
import info.soslive.stream.data.remote.dto.toDomain
import info.soslive.stream.domain.model.ActiveEvent
import info.soslive.stream.domain.model.Comment
import info.soslive.stream.domain.model.CommentPage
import info.soslive.stream.domain.model.Event
import info.soslive.stream.domain.model.EventType
import info.soslive.stream.domain.model.GeoPoint
import info.soslive.stream.domain.model.Photo
import info.soslive.stream.domain.repository.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepositoryImpl @Inject constructor(
    private val api: SosLiveApi,
    private val activeEventStorage: ActiveEventStorage,
    private val clock: Clock,
    private val json: Json,
) : EventRepository {

    override val activeEvent: Flow<ActiveEvent?> = activeEventStorage.activeEvent
        .map { event -> event?.takeUnless { it.isExpired(clock.millis(), AppConfig.ACTIVE_EVENT_WINDOW_MILLIS) } }
        .distinctUntilChanged()

    override suspend fun createEvent(type: EventType, location: GeoPoint?): Result<Event> =
        apiCall(json) { api.createEvent(CreateEventRequest(type.name, location?.lat, location?.lng)) }
            .map { dto ->
                val event = dto.toDomain()
                activeEventStorage.save(ActiveEvent(event.id, event.type, clock.millis(), event.shareUrl))
                event
            }

    override suspend fun clearActiveEvent() = activeEventStorage.clear()

    override suspend fun myEvents(): Result<List<Event>> =
        apiCall(json) { api.events() }.map { list -> list.items.map { it.toDomain() } }

    override suspend fun event(id: Long): Result<Event> =
        apiCall(json) { api.event(id) }.map { it.toDomain() }

    override suspend fun sendLocation(id: Long, location: GeoPoint): Result<Unit> =
        apiCall(json) { api.updateLocation(id, LocationUpdateRequest(location.lat, location.lng, location.accuracy)) }
            .map { }

    override suspend fun stop(id: Long): Result<Event> =
        apiCall(json) { api.stopEvent(id) }.map { it.toDomain() }

    override suspend fun comments(id: Long, sinceId: Long?): Result<CommentPage> =
        apiCall(json) { api.comments(id, sinceId) }.map { it.toDomain() }

    override suspend fun addComment(id: Long, message: String): Result<Comment> =
        apiCall(json) { api.addComment(id, AddCommentRequest(message.trim())) }.map { it.toDomain() }

    override suspend fun photos(id: Long): Result<List<Photo>> =
        apiCall(json) { api.photos(id) }.map { list -> list.items.map { it.toDomain() } }

    override suspend fun uploadPhoto(id: Long, file: File): Result<Photo> = apiCall(json) {
        val part = MultipartBody.Part.createFormData("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
        api.uploadPhoto(id, part)
    }.map { it.toDomain() }
}
