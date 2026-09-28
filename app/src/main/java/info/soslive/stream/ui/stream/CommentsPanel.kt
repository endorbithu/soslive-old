package info.soslive.stream.ui.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Badge
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.soslive.stream.R
import info.soslive.stream.domain.model.Comment
import info.soslive.stream.ui.common.formatDateTime

@Composable
fun CommentsPanel(
    comments: List<Comment>,
    unread: Int,
    expanded: Boolean,
    draft: String,
    sending: Boolean,
    onToggle: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.comments_title, comments.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (unread > 0) Badge { Text(unread.toString()) }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                CommentList(comments, Modifier.heightIn(max = 220.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        placeholder = { Text(stringResource(R.string.comment_hint)) },
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

@Composable
fun CommentList(comments: List<Comment>, modifier: Modifier = Modifier) {
    if (comments.isEmpty()) {
        Text(stringResource(R.string.comments_empty), style = MaterialTheme.typography.bodySmall, modifier = modifier.padding(vertical = 8.dp))
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(comments.size) { listState.animateScrollToItem(comments.lastIndex) }
    LazyColumn(state = listState, modifier = modifier) {
        items(comments, key = { it.id }) { comment ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(
                    "${comment.authorName} · ${formatDateTime(comment.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (comment.fromOwner) FontWeight.Normal else FontWeight.Bold,
                )
                Text(comment.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
