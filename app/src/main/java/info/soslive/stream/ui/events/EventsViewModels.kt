package info.soslive.stream.ui.events

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.data.remote.toUiText
import info.soslive.stream.domain.model.Comment
import info.soslive.stream.domain.model.Event
import info.soslive.stream.domain.model.Photo
import info.soslive.stream.domain.repository.EventRepository
import info.soslive.stream.ui.navigation.EventDetailRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EventsUiState(
    val loading: Boolean = true,
    val events: List<Event> = emptyList(),
    val error: UiText? = null,
)

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val eventRepository: EventRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EventsUiState())
    val state: StateFlow<EventsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            eventRepository.myEvents()
                .onSuccess { events -> _state.update { it.copy(loading = false, events = events) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.toUiText()) } }
        }
    }
}

data class EventDetailUiState(
    val loading: Boolean = true,
    val event: Event? = null,
    val comments: List<Comment> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val error: UiText? = null,
)

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventRepository: EventRepository,
) : ViewModel() {

    val eventId: Long = savedStateHandle.toRoute<EventDetailRoute>().id

    private val _state = MutableStateFlow(EventDetailUiState())
    val state: StateFlow<EventDetailUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val event = async { eventRepository.event(eventId) }
            val comments = async { eventRepository.comments(eventId) }
            val photos = async { eventRepository.photos(eventId) }
            val eventResult = event.await()
            _state.update {
                it.copy(
                    loading = false,
                    event = eventResult.getOrNull() ?: it.event,
                    comments = comments.await().getOrNull()?.items ?: it.comments,
                    photos = photos.await().getOrNull() ?: it.photos,
                    error = eventResult.exceptionOrNull()?.toUiText(),
                )
            }
        }
    }

    fun onDraftChange(value: String) = _state.update { it.copy(draft = value.take(1000)) }

    fun sendComment() {
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.sending) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            eventRepository.addComment(eventId, text)
                .onSuccess { comment -> _state.update { it.copy(draft = "", comments = it.comments + comment) } }
                .onFailure { error -> _state.update { it.copy(error = error.toUiText()) } }
            _state.update { it.copy(sending = false) }
        }
    }
}
