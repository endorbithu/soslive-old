package info.soslive.stream.ui.events

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import info.soslive.stream.ui.stream.EntryList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    onBack: () -> Unit,
    onOpenEvent: (fileId: String, title: String) -> Unit,
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
                    items(state.events, key = { it.fileId }) { event ->
                        ListItem(
                            headlineContent = { Text(event.title) },
                            supportingContent = { Text(stringResource(R.string.utc_hint)) },
                            modifier = Modifier.clickable { onOpenEvent(event.fileId, event.title) },
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
                title = { Text(viewModel.title, maxLines = 1) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.link)
                        context.startActivity(Intent.createChooser(share, null))
                    }) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share_link)) }
                    IconButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.link)))
                        } catch (_: ActivityNotFoundException) {
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.action_open_web)) }
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            Text(viewModel.link, style = MaterialTheme.typography.bodySmall)
            val document = state.document
            when {
                document != null -> {
                    Text(
                        if (document.stream.isBlank()) stringResource(R.string.event_no_stream) else stringResource(R.string.event_stream, document.stream),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider()
                    EntryList(document.entries, Modifier.weight(1f))
                }
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
}
