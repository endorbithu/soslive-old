package info.soslive.stream.ui.stream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.api.ResolvableApiException
import dagger.hilt.android.lifecycle.HiltViewModel
import info.soslive.stream.R
import info.soslive.stream.core.config.AppConfig
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.data.remote.toUiText
import info.soslive.stream.domain.model.ActiveEvent
import info.soslive.stream.domain.model.Comment
import info.soslive.stream.domain.model.EventType
import info.soslive.stream.domain.repository.AuthRepository
import info.soslive.stream.domain.repository.EventRepository
import info.soslive.stream.location.LocationTracker
import info.soslive.stream.sms.SosSmsSender
import info.soslive.stream.stream.StreamController
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

enum class LivePhase { IDLE, CREATING_EVENT, CONNECTING, LIVE, STOPPING }

data class StreamUiState(
    val phase: LivePhase = LivePhase.IDLE,
    /** Event currently being streamed (SOS / LIVE). */
    val liveEventId: Long? = null,
    val liveType: EventType? = null,
    /** Open incident - comments are shown and photos are added to it. */
    val activeEvent: ActiveEvent? = null,
    val comments: List<Comment> = emptyList(),
    val unreadComments: Int = 0,
    val commentsExpanded: Boolean = false,
    val commentDraft: String = "",
    val sendingComment: Boolean = false,
    val preparingPhoto: Boolean = false,
    val uploadingPhoto: Boolean = false,
) {
    val isStreamActive: Boolean get() = phase == LivePhase.CONNECTING || phase == LivePhase.LIVE
}

sealed interface StreamEffect {
    data class StartPublishing(val publishUrl: String) : StreamEffect
    data object StopPublishing : StreamEffect
    data class OpenSmsApp(val numbers: List<String>, val text: String) : StreamEffect
    data class TakePhoto(val eventId: Long) : StreamEffect
    data class Message(val text: UiText) : StreamEffect
}

/**
 * The main screen's logic: SOS / LIVE streaming, photo incidents, location reporting, SOS SMS and
 * comments. The camera itself ([StreamController]) belongs to the UI - this ViewModel asks it to
 * start/stop via [effects] and receives its callbacks as the [StreamController.Listener].
 */
@HiltViewModel
class StreamViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val eventRepository: EventRepository,
    private val locationTracker: LocationTracker,
    private val smsSender: SosSmsSender,
) : ViewModel(), StreamController.Listener {

    private val _state = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = _state.asStateFlow()

    private val _effects = Channel<StreamEffect>(Channel.BUFFERED)
    val effects: Flow<StreamEffect> = _effects.receiveAsFlow()

    private var publishUrl: String? = null
    private var retries = 0
    private var locationJob: Job? = null
    private var commentsJob: Job? = null

    init {
        viewModelScope.launch {
            eventRepository.activeEvent.collect { active ->
                val previousId = _state.value.activeEvent?.id
                _state.update { it.copy(activeEvent = active) }
                if (active?.id != previousId) {
                    _state.update { it.copy(comments = emptyList(), unreadComments = 0, commentDraft = "") }
                    commentsJob?.cancel()
                    if (active != null) startCommentPolling(active.id)
                }
            }
        }
    }

    // ---------------------------------------------------------------- streaming

    fun startLive(type: EventType) {
        require(type != EventType.PHOTO)
        if (_state.value.phase != LivePhase.IDLE) return
        _state.update { it.copy(phase = LivePhase.CREATING_EVENT) }

        viewModelScope.launch {
            val location = locationTracker.currentLocation()
            eventRepository.createEvent(type, location)
                .onSuccess { event ->
                    val url = event.stream?.publishUrl
                    if (url == null) {
                        _state.update { it.copy(phase = LivePhase.IDLE) }
                        message(UiText.Res(R.string.stream_no_target))
                        return@onSuccess
                    }
                    publishUrl = url
                    retries = 0
                    _state.update { it.copy(phase = LivePhase.CONNECTING, liveEventId = event.id, liveType = type) }
                    _effects.send(StreamEffect.StartPublishing(url))
                    startLocationUpdates(event.id)
                    // The SMS goes out right away - it must not depend on the stream connecting.
                    if (type == EventType.SOS) sendSosSms(event.shareUrl)
                }
                .onFailure { error ->
                    _state.update { it.copy(phase = LivePhase.IDLE) }
                    message(error.toUiText())
                }
        }
    }

    fun stopLive() = stopLiveInternal(publisherAlreadyStopped = false)

    private fun stopLiveInternal(publisherAlreadyStopped: Boolean) {
        val current = _state.value
        val eventId = current.liveEventId ?: return
        if (!current.isStreamActive) return

        _state.update { it.copy(phase = LivePhase.STOPPING) }
        locationJob?.cancel()
        publishUrl = null

        viewModelScope.launch {
            if (!publisherAlreadyStopped) _effects.send(StreamEffect.StopPublishing)
            eventRepository.stop(eventId)
            // The incident stays active (comments, extra photos) until it expires or "new incident".
            _state.update { it.copy(phase = LivePhase.IDLE, liveEventId = null, liveType = null) }
            message(UiText.Res(R.string.stream_stopped))
        }
    }

    /** Called by the UI when the device cannot prepare the encoders. */
    fun onEncoderUnavailable() {
        message(UiText.Res(R.string.stream_encoder_error))
        stopLiveInternal(publisherAlreadyStopped = true)
    }

    override fun onStreamConnected() {
        retries = 0
        if (_state.value.phase == LivePhase.CONNECTING) {
            _state.update { it.copy(phase = LivePhase.LIVE) }
            message(UiText.Res(R.string.stream_started))
        }
    }

    override fun onStreamFailed(reason: String) = handleConnectionLoss(reason)

    override fun onStreamDisconnected() {
        // Disconnects are also reported after an intentional stop / before a retry - only a live stream matters.
        if (_state.value.phase == LivePhase.LIVE) handleConnectionLoss("disconnected")
    }

    override fun onStreamInterrupted() = stopLiveInternal(publisherAlreadyStopped = true)

    private fun handleConnectionLoss(reason: String) {
        if (!_state.value.isStreamActive) return
        val url = publishUrl ?: return
        if (retries < AppConfig.STREAM_MAX_RETRIES) {
            retries++
            _state.update { it.copy(phase = LivePhase.CONNECTING) }
            viewModelScope.launch {
                message(UiText.Res(R.string.stream_reconnecting, retries, AppConfig.STREAM_MAX_RETRIES))
                delay(AppConfig.STREAM_RETRY_DELAY_MILLIS)
                if (_state.value.phase == LivePhase.CONNECTING && publishUrl == url) {
                    _effects.send(StreamEffect.StartPublishing(url))
                }
            }
        } else {
            message(UiText.Res(R.string.stream_failed, reason))
            stopLiveInternal(publisherAlreadyStopped = true)
        }
    }

    private fun startLocationUpdates(eventId: Long) {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationTracker.updates().collect { point -> eventRepository.sendLocation(eventId, point) }
        }
    }

    private suspend fun sendSosSms(shareUrl: String) {
        val user = authRepository.currentUser.first() ?: return
        if (user.sosContacts.isEmpty()) {
            message(UiText.Res(R.string.sos_no_contacts))
            return
        }
        val text = smsSender.buildMessage(user.sosMessage, shareUrl)
        if (smsSender.canSendDirectly()) {
            val sent = smsSender.sendDirect(user.sosContacts, text)
            message(UiText.Res(R.string.sos_sms_sent, sent))
        } else {
            _effects.send(StreamEffect.OpenSmsApp(user.sosContacts, text))
        }
    }

    // ---------------------------------------------------------------- photos

    /** Adds a photo to the open incident, or opens a new PHOTO incident first. */
    fun takePhoto() {
        val current = _state.value
        if (current.phase != LivePhase.IDLE || current.preparingPhoto || current.uploadingPhoto) return
        _state.update { it.copy(preparingPhoto = true) }

        viewModelScope.launch {
            val eventId = current.activeEvent?.id ?: run {
                val location = locationTracker.currentLocation()
                val created = eventRepository.createEvent(EventType.PHOTO, location)
                val event = created.getOrElse { error ->
                    _state.update { it.copy(preparingPhoto = false) }
                    message(error.toUiText())
                    return@launch
                }
                if (location == null) sendFirstFix(event.id)
                event.id
            }
            _state.update { it.copy(preparingPhoto = false) }
            _effects.send(StreamEffect.TakePhoto(eventId))
        }
    }

    fun onPhotoCaptured(eventId: Long, file: File) {
        _state.update { it.copy(uploadingPhoto = true) }
        viewModelScope.launch {
            eventRepository.uploadPhoto(eventId, file)
                .onSuccess { message(UiText.Res(R.string.photo_uploaded)) }
                .onFailure { message(it.toUiText()) }
            file.delete()
            _state.update { it.copy(uploadingPhoto = false) }
        }
    }

    fun onPhotoCancelled(file: File) {
        file.delete()
    }

    /** The legacy app reported the location of a photo incident once, as soon as a fix arrived. */
    private fun sendFirstFix(eventId: Long) {
        viewModelScope.launch {
            val point = withTimeoutOrNull(60_000) { locationTracker.updates().first() } ?: return@launch
            eventRepository.sendLocation(eventId, point)
        }
    }

    // ---------------------------------------------------------------- incident / comments

    /** "New incident": closes the open one so the next photo starts a new event. */
    fun newIncident() {
        if (_state.value.phase != LivePhase.IDLE) return
        viewModelScope.launch { eventRepository.clearActiveEvent() }
    }

    fun toggleComments() {
        _state.update { it.copy(commentsExpanded = !it.commentsExpanded, unreadComments = 0) }
    }

    fun onCommentDraftChange(value: String) {
        _state.update { it.copy(commentDraft = value.take(1000)) }
    }

    fun sendComment() {
        val current = _state.value
        val eventId = current.activeEvent?.id ?: return
        val text = current.commentDraft.trim()
        if (text.isEmpty() || current.sendingComment) return
        _state.update { it.copy(sendingComment = true) }
        viewModelScope.launch {
            eventRepository.addComment(eventId, text)
                .onSuccess {
                    _state.update { it.copy(commentDraft = "") }
                    refreshComments(eventId)
                }
                .onFailure { message(it.toUiText()) }
            _state.update { it.copy(sendingComment = false) }
        }
    }

    private fun startCommentPolling(eventId: Long) {
        commentsJob = viewModelScope.launch {
            while (isActive) {
                refreshComments(eventId)
                delay(AppConfig.COMMENT_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun refreshComments(eventId: Long) {
        val sinceId = _state.value.comments.lastOrNull()?.id
        val page = eventRepository.comments(eventId, sinceId).getOrNull() ?: return
        if (page.items.isEmpty()) return
        _state.update { s ->
            if (s.activeEvent?.id != eventId) return@update s
            val known = s.comments.mapTo(HashSet()) { it.id }
            val fresh = page.items.filter { it.id !in known }
            s.copy(
                comments = s.comments + fresh,
                unreadComments = if (s.commentsExpanded) 0 else s.unreadComments + fresh.count { !it.fromOwner },
            )
        }
    }

    // ---------------------------------------------------------------- misc

    suspend fun locationSettingsResolution(): ResolvableApiException? = locationTracker.settingsResolution()

    private fun message(text: UiText) {
        _effects.trySend(StreamEffect.Message(text))
    }
}
