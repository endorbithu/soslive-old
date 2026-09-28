package info.soslive.stream.ui.events

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.soslive.stream.R
import info.soslive.stream.domain.model.Event
import info.soslive.stream.domain.model.EventStatus
import info.soslive.stream.domain.model.EventType
import info.soslive.stream.ui.common.formatDateTime
import info.soslive.stream.ui.stream.CommentList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    onBack: () -> Unit,
    onOpenEvent: (Long) -> Unit,
    viewModel: EventsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_my_events)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.events.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.events.isEmpty() -> Text(state.error!!.asString(), Modifier.align(Alignment.Center).padding(24.dp))
                state.events.isEmpty() -> Text(stringResource(R.string.events_empty), Modifier.align(Alignment.Center).padding(24.dp))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.events, key = { it.id }) { event ->
                        ListItem(
                            headlineContent = { Text("#${event.id} · ${typeLabel(event.type)}") },
                            supportingContent = { Text(formatDateTime(event.createdAt)) },
                            trailingContent = { StatusChip(event.status) },
                            modifier = Modifier.clickable { onOpenEvent(event.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    onBack: () -> Unit,
    viewModel: EventDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_with_event, viewModel.eventId)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    state.event?.let { event ->
                        IconButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.shareUrl)))
                            } catch (_: ActivityNotFoundException) {
                            }
                        }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.action_open_web)) }
                    }
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            val event = state.event
            if (event == null) {
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            EventSummary(event, photoCount = state.photos.size)
            HorizontalDivider()
            Text(stringResource(R.string.comments_title, state.comments.size), style = MaterialTheme.typography.titleSmall)
            CommentList(state.comments, Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = viewModel::onDraftChange,
                    placeholder = { Text(stringResource(R.string.comment_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                IconButton(onClick = viewModel::sendComment, enabled = state.draft.isNotBlank() && !state.sending) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                }
            }
        }
    }
}

@Composable
private fun EventSummary(event: Event, photoCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(typeLabel(event.type), style = MaterialTheme.typography.titleMedium)
            StatusChip(event.status)
        }
        Text(stringResource(R.string.event_started, formatDateTime(event.createdAt)))
        event.stoppedAt?.let { Text(stringResource(R.string.event_stopped, formatDateTime(it))) }
        Text(
            event.lastLocation?.let { stringResource(R.string.event_location, it.lat, it.lng) } ?: stringResource(R.string.event_no_location),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.event_photos, photoCount), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusChip(status: EventStatus) {
    val label = when (status) {
        EventStatus.LIVE -> R.string.status_live
        EventStatus.OPEN -> R.string.status_open
        EventStatus.STOPPED -> R.string.status_stopped
        EventStatus.UNKNOWN -> R.string.status_unknown
    }
    AssistChip(onClick = {}, label = { Text(stringResource(label)) })
}

@Composable
private fun typeLabel(type: EventType): String = stringResource(
    when (type) {
        EventType.SOS -> R.string.type_sos
        EventType.LIVE -> R.string.type_live
        EventType.PHOTO -> R.string.type_photo
    },
)

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
}
