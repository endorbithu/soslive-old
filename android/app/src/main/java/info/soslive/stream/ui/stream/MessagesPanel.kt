package info.soslive.stream.ui.stream

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.soslive.stream.R
import info.soslive.stream.drive.EventEntry
import info.soslive.stream.ui.common.formatDateTime
import java.time.Instant

/** Open incident: share its link and add the owner's own messages to the event page. */
@Composable
fun MessagesPanel(
    link: String,
    messages: List<EventEntry>,
    expanded: Boolean,
    draft: String,
    sending: Boolean,
    onToggle: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.messages_title, messages.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                context.startActivity(Intent.createChooser(share, null))
            }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share_link))
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                EntryList(messages, Modifier.heightIn(max = 200.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        placeholder = { Text(stringResource(R.string.message_hint)) },
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                    )
                    IconButton(onClick = onSend, enabled = draft.isNotBlank() && !sending) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                    }
                }
            }
        }
    }
}

/** Entries of an event file (position / message / image; unknown types are skipped, like on the web). */
@Composable
fun EntryList(entries: List<EventEntry>, modifier: Modifier = Modifier) {
    val known = entries.filter { it.type in setOf("pos", "msg", "img") }
    if (known.isEmpty()) {
        Text(stringResource(R.string.entries_empty), style = MaterialTheme.typography.bodySmall, modifier = modifier.padding(vertical = 8.dp))
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(known.size) { listState.animateScrollToItem(known.lastIndex) }
    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(known) { _, entry ->
            val time = entry.time?.let { runCatching { formatDateTime(Instant.parse(it)) }.getOrNull() }.orEmpty()
            Column(Modifier.padding(vertical = 4.dp)) {
                when (entry.type) {
                    "msg" -> {
                        Text("${entry.string("name").orEmpty()} · $time", style = MaterialTheme.typography.labelSmall)
                        Text(entry.string("text").orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }
                    "pos" -> Text(
                        stringResource(R.string.entry_position, time, entry.double("lat") ?: 0.0, entry.double("lng") ?: 0.0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    "img" -> Text(stringResource(R.string.entry_image, time), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
