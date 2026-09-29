package info.soslive.stream.ui.events

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.core.ui.toUiText
import info.soslive.stream.drive.EventDocument
import info.soslive.stream.drive.EventSummary
import info.soslive.stream.drive.SosliveDrive
import info.soslive.stream.ui.navigation.EventDetailRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EventsUiState(
    val loading: Boolean = true,
    val events: List<EventSummary> = emptyList(),
    val error: UiText? = null,
)

/** The event files in the user's SOSlive folder, newest first. */
@HiltViewModel
class EventsViewModel @Inject constructor(
    private val drive: SosliveDrive,
) : ViewModel() {

    private val _state = MutableStateFlow(EventsUiState())
    val state: StateFlow<EventsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { drive.listEvents() }
                .onSuccess { events -> _state.update { it.copy(loading = false, events = events) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.toUiText()) } }
        }
    }
}

data class EventDetailUiState(
    val loading: Boolean = true,
    val document: EventDocument? = null,
    val error: UiText? = null,
)

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val drive: SosliveDrive,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<EventDetailRoute>()
    val title: String = route.title
    val link: String = drive.link(route.fileId)

    private val _state = MutableStateFlow(EventDetailUiState())
    val state: StateFlow<EventDetailUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { drive.readEvent(route.fileId) }
                .onSuccess { doc -> _state.update { it.copy(loading = false, document = doc) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.toUiText()) } }
        }
    }
}
