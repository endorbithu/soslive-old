package info.soslive.stream.ui.stream

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.api.ResolvableApiException
import dagger.hilt.android.lifecycle.HiltViewModel
import info.soslive.stream.R
import info.soslive.stream.auth.AccountStore
import info.soslive.stream.auth.ActiveEvent
import info.soslive.stream.auth.EventType
import info.soslive.stream.core.config.AppConfig
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.core.ui.toUiText
import info.soslive.stream.drive.DriveApi
import info.soslive.stream.drive.DriveNotFoundException
import info.soslive.stream.drive.EventDocument
import info.soslive.stream.drive.EventEntry
import info.soslive.stream.drive.EventWriter
import info.soslive.stream.drive.SosConfig
import info.soslive.stream.drive.SosliveDrive
import info.soslive.stream.drive.eventTitle
import info.soslive.stream.location.LocationTracker
import info.soslive.stream.sms.SosSmsSender
import info.soslive.stream.stream.StreamController
import info.soslive.stream.stream.StreamProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

enum class LivePhase { IDLE, CREATING_EVENT, CONNECTING, LIVE, STOPPING }

data class StreamUiState(
    val phase: LivePhase = LivePhase.IDLE,
    val liveType: EventType? = null,
    /** Open incident - photos and messages go to its file. */
    val activeEvent: ActiveEvent? = null,
    /** The owner's own messages in the open incident. */
    val messages: List<EventEntry> = emptyList(),
    val messagesExpanded: Boolean = false,
    val messageDraft: String = "",
    val sendingMessage: Boolean = false,
    val preparingPhoto: Boolean = false,
    val uploadingPhoto: Boolean = false,
) {
    val isStreamActive: Boolean get() = phase == LivePhase.CONNECTING || phase == LivePhase.LIVE
}

sealed interface StreamEffect {
    data class StartPublishing(val publishUrl: String) : StreamEffect
    data object StopPublishing : StreamEffect
    /** Pre-filled composers to open (SMS when it could not be sent directly, e-mail). */
    data class OpenComposers(val intents: List<Intent>) : StreamEffect
    data class TakePhoto(val eventFileId: String) : StreamEffect
    data class Message(val text: UiText) : StreamEffect
}

/**
 * Main screen: SOS / LIVE streaming, photo incidents, messages - all written to the event's JSON
 * file on the user's Google Drive. No SOSlive backend is called.
 */
@HiltViewModel
class StreamViewModel @Inject constructor(
    private val accountStore: AccountStore,
    private val drive: SosliveDrive,
    private val driveApi: DriveApi,
    private val streamProvider: StreamProvider,
    private val locationTracker: LocationTracker,
    private val smsSender: SosSmsSender,
    private val clock: Clock,
) : ViewModel(), StreamController.Listener {

    private val _state = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = _state.asStateFlow()

    private val _effects = Channel<StreamEffect>(Channel.BUFFERED)
    val effects: Flow<StreamEffect> = _effects.receiveAsFlow()

    private var writer: EventWriter? = null
    private var publishUrl: String? = null
    private var retries = 0
    private var locationJob: Job? = null

    init {
        viewModelScope.launch {
            accountStore.activeEvent.collect { active ->
                val valid = active?.takeUnless { it.isExpired(clock.millis(), AppConfig.ACTIVE_EVENT_WINDOW_MILLIS) }
                _state.update {
                    it.copy(
                        activeEvent = valid,
                        messages = if (valid?.fileId == writer?.fileId) it.messages else emptyList(),
                    )
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
            try {
                val location = locationTracker.currentLocation()
                val stream = streamProvider.createStream()
                val start = Instant.now(clock)
                val document = EventDocument(
                    stream = stream.playbackUrl,
                    entries = listOfNotNull(location?.let { EventEntry.position(start, it.lat, it.lng) }),
                )
                val created = drive.createEvent(start, document)
                if (!created.shared) message(UiText.Res(R.string.share_failed, created.shareError.orEmpty()))
                val eventWriter = openWriter(created.fileId, document)
                accountStore.setActiveEvent(ActiveEvent(created.fileId, type, eventTitle(created.name), created.link, clock.millis()))

                publishUrl = stream.publishUrl
                retries = 0
                _state.update { it.copy(phase = LivePhase.CONNECTING, liveType = type) }
                _effects.send(StreamEffect.StartPublishing(stream.publishUrl))
                startLocationUpdates(eventWriter)
                if (type == EventType.SOS) notifyContacts(created.link)
                rotateInBackground()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = LivePhase.IDLE) }
                message(e.toUiText())
            }
        }
    }

    fun stopLive() = stopLiveInternal(publisherAlreadyStopped = false)

    private fun stopLiveInternal(publisherAlreadyStopped: Boolean) {
        if (!_state.value.isStreamActive) return
        _state.update { it.copy(phase = LivePhase.STOPPING) }
        locationJob?.cancel()
        publishUrl = null
        viewModelScope.launch {
            if (!publisherAlreadyStopped) _effects.send(StreamEffect.StopPublishing)
            // Final full upload; the file and the link stay.
            writer?.close()
            _state.update { it.copy(phase = LivePhase.IDLE, liveType = null) }
            message(UiText.Res(R.string.stream_stopped))
        }
    }

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

    /** Position entries every 30 s at most (EventWriter throttles as well). */
    private fun startLocationUpdates(eventWriter: EventWriter) {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationTracker.updates().collect { point -> eventWriter.addPosition(point.lat, point.lng) }
        }
    }

    // ---------------------------------------------------------------- notifications

    private suspend fun config(): SosConfig =
        drive.cachedConfig() ?: runCatching { drive.readRemoteConfig() }.getOrNull() ?: SosConfig()

    /** Sends the link to the config.json contacts: SMS directly if allowed, otherwise via composers. */
    private suspend fun notifyContacts(link: String) {
        val config = config()
        if (config.notificationPhones.isEmpty() && config.notificationEmails.isEmpty()) {
            message(UiText.Res(R.string.notify_no_contacts))
            return
        }
        val text = smsSender.sosText(link)
        val composers = mutableListOf<Intent>()
        if (config.notificationEmails.isNotEmpty()) {
            composers += SosSmsSender.emailIntent(config.notificationEmails, smsSender.sosSubject(), text)
        }
        if (config.notificationPhones.isNotEmpty()) {
            if (smsSender.canSendDirectly()) {
                val sent = smsSender.sendDirect(config.notificationPhones, text)
                message(UiText.Res(R.string.sms_sent, sent))
            } else {
                // Last in the list = shown first.
                composers += SosSmsSender.smsIntent(config.notificationPhones, text)
            }
        }
        if (composers.isNotEmpty()) _effects.send(StreamEffect.OpenComposers(composers))
        message(UiText.Res(R.string.mute_tip))
    }

    private fun rotateInBackground() {
        viewModelScope.launch {
            runCatching { drive.rotate(config().maxEvents) }
        }
    }

    // ---------------------------------------------------------------- incident file

    private fun openWriter(fileId: String, document: EventDocument): EventWriter {
        val eventWriter = EventWriter(
            drive = driveApi,
            fileId = fileId,
            initial = document,
            scope = viewModelScope,
            clock = clock,
            onStopped = { error -> viewModelScope.launch { onWriterStopped(fileId, error) } },
        )
        writer = eventWriter
        _state.update { it.copy(messages = document.entries.filter { e -> e.type == "msg" }) }
        return eventWriter
    }

    private suspend fun onWriterStopped(fileId: String, error: Throwable) {
        message(error.toUiText())
        if (error is DriveNotFoundException && _state.value.activeEvent?.fileId == fileId) {
            accountStore.setActiveEvent(null)
        }
    }

    /** Writer for the open incident; after an app restart the document is read back from Drive. */
    private suspend fun writerFor(active: ActiveEvent): EventWriter? {
        writer?.takeIf { it.fileId == active.fileId && !it.stopped }?.let { return it }
        return try {
            openWriter(active.fileId, drive.readEvent(active.fileId))
        } catch (e: DriveNotFoundException) {
            accountStore.setActiveEvent(null)
            message(e.toUiText())
            null
        } catch (e: Exception) {
            message(e.toUiText())
            null
        }
    }

    // ---------------------------------------------------------------- photos

    fun takePhoto() {
        val current = _state.value
        if (current.phase != LivePhase.IDLE || current.preparingPhoto || current.uploadingPhoto) return
        _state.update { it.copy(preparingPhoto = true) }
        viewModelScope.launch {
            try {
                val active = current.activeEvent
                val fileId = if (active != null && writerFor(active) != null) {
                    active.fileId
                } else {
                    val location = locationTracker.currentLocation()
                    val start = Instant.now(clock)
                    val document = EventDocument(entries = listOfNotNull(location?.let { EventEntry.position(start, it.lat, it.lng) }))
                    val created = drive.createEvent(start, document)
                    if (!created.shared) message(UiText.Res(R.string.share_failed, created.shareError.orEmpty()))
                    val eventWriter = openWriter(created.fileId, document)
                    accountStore.setActiveEvent(ActiveEvent(created.fileId, EventType.PHOTO, eventTitle(created.name), created.link, clock.millis()))
                    if (location == null) sendFirstFix(eventWriter)
                    rotateInBackground()
                    created.fileId
                }
                _effects.send(StreamEffect.TakePhoto(fileId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(e.toUiText())
            } finally {
                _state.update { it.copy(preparingPhoto = false) }
            }
        }
    }

    fun onPhotoCaptured(eventFileId: String, file: File) {
        _state.update { it.copy(uploadingPhoto = true) }
        viewModelScope.launch {
            try {
                val active = _state.value.activeEvent?.takeIf { it.fileId == eventFileId }
                val eventWriter = active?.let { writerFor(it) }
                if (eventWriter == null) {
                    message(UiText.Res(R.string.error_drive_missing))
                } else {
                    val url = drive.uploadImage(file.readBytes())
                    eventWriter.addImage(url)
                    message(UiText.Res(R.string.photo_uploaded))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(e.toUiText())
            } finally {
                file.delete()
                _state.update { it.copy(uploadingPhoto = false) }
            }
        }
    }

    fun onPhotoCancelled(file: File) {
        file.delete()
    }

    /** A photo incident without a fix yet gets its first position as soon as one arrives. */
    private fun sendFirstFix(eventWriter: EventWriter) {
        viewModelScope.launch {
            val point = withTimeoutOrNull(60_000) { locationTracker.updates().first() } ?: return@launch
            eventWriter.addPosition(point.lat, point.lng, force = true)
        }
    }

    // ---------------------------------------------------------------- messages

    fun toggleMessages() = _state.update { it.copy(messagesExpanded = !it.messagesExpanded) }

    fun onMessageDraftChange(value: String) = _state.update { it.copy(messageDraft = value.take(1000)) }

    /** The owner's own message, shown on the event page ("name" is never an e-mail / phone). */
    fun sendMessage() {
        val current = _state.value
        val active = current.activeEvent ?: return
        val text = current.messageDraft.trim()
        if (text.isEmpty() || current.sendingMessage) return
        _state.update { it.copy(sendingMessage = true) }
        viewModelScope.launch {
            val eventWriter = writerFor(active)
            if (eventWriter != null) {
                val name = accountStore.currentAccount()?.name?.takeUnless { '@' in it }?.substringBefore(' ') ?: "SOSlive"
                eventWriter.addMessage(name, text)
                _state.update {
                    it.copy(messageDraft = "", messages = eventWriter.current.entries.filter { e -> e.type == "msg" })
                }
            }
            _state.update { it.copy(sendingMessage = false) }
        }
    }

    /** "New incident": closes the open one so the next photo starts a new event file. */
    fun newIncident() {
        if (_state.value.phase != LivePhase.IDLE) return
        viewModelScope.launch {
            writer?.close()
            writer = null
            accountStore.setActiveEvent(null)
        }
    }

    // ---------------------------------------------------------------- misc

    suspend fun locationSettingsResolution(): ResolvableApiException? = locationTracker.settingsResolution()

    private fun message(text: UiText) {
        _effects.trySend(StreamEffect.Message(text))
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        // Best effort: push what is still pending (e.g. the last position) before the scope dies.
        writer?.takeIf { it.hasPendingChanges }?.let { pending ->
            GlobalScope.launch { runCatching { pending.close() } }
        }
        super.onCleared()
    }
}
