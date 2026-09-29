package info.soslive.stream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.soslive.stream.R
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.stream.StreamSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it.asString(context))
            viewModel.messageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.account?.let { account ->
                Text(account.name, style = MaterialTheme.typography.titleMedium)
                Text(account.email, style = MaterialTheme.typography.bodySmall)
                if (account.simulated) {
                    Text(stringResource(R.string.simulated_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.settings_notify_section), style = MaterialTheme.typography.titleMedium)
            MultiLineField(
                value = state.phones,
                onChange = viewModel::onPhonesChange,
                label = R.string.field_notification_phones,
                hint = R.string.field_notification_phones_hint,
                error = state.phonesError,
                keyboard = KeyboardType.Phone,
            )
            MultiLineField(
                value = state.emails,
                onChange = viewModel::onEmailsChange,
                label = R.string.field_notification_emails,
                hint = R.string.field_notification_emails_hint,
                error = state.emailsError,
                keyboard = KeyboardType.Email,
            )
            OutlinedTextField(
                value = state.maxEvents,
                onValueChange = viewModel::onMaxEventsChange,
                label = { Text(stringResource(R.string.field_max_events)) },
                isError = state.maxEventsError != null,
                supportingText = { Text(state.maxEventsError?.asString() ?: stringResource(R.string.field_max_events_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::save, enabled = !state.saving && !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_save))
            }
            Text(stringResource(R.string.settings_web_note), style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            StreamSection(
                stream = state.stream,
                errors = state.streamErrors,
                onChange = viewModel::onStreamChange,
                onSave = viewModel::saveStream,
            )

            HorizontalDivider()
            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Text(stringResource(R.string.action_logout), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.logout_confirm)) },
            text = { Text(stringResource(R.string.logout_note)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.action_logout)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun MultiLineField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    hint: Int,
    error: UiText?,
    keyboard: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = { Text(error?.asString() ?: stringResource(hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The user's own streaming service (YouTube, Twitch, ...). Stored only on this phone. */
@Composable
private fun StreamSection(
    stream: StreamSettings,
    errors: Set<StreamSettings.Field>,
    onChange: (StreamSettings) -> Unit,
    onSave: () -> Unit,
) {
    Text(stringResource(R.string.settings_stream_section), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.settings_stream_note), style = MaterialTheme.typography.bodySmall)
    UrlField(stream.rtmpUrl, { onChange(stream.copy(rtmpUrl = it)) }, R.string.field_stream_rtmp_url, R.string.field_stream_rtmp_url_hint, StreamSettings.Field.RTMP_URL in errors)
    OutlinedTextField(
        value = stream.streamKey,
        onValueChange = { onChange(stream.copy(streamKey = it.trim())) },
        label = { Text(stringResource(R.string.field_stream_key)) },
        supportingText = { Text(stringResource(R.string.field_stream_key_hint)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    UrlField(stream.playbackUrl, { onChange(stream.copy(playbackUrl = it)) }, R.string.field_stream_playback_url, R.string.field_stream_playback_url_hint, StreamSettings.Field.PLAYBACK_URL in errors)
    UrlField(stream.pageUrl, { onChange(stream.copy(pageUrl = it)) }, R.string.field_stream_page_url, R.string.field_stream_page_url_hint, StreamSettings.Field.PAGE_URL in errors)
    UrlField(stream.recordingUrl, { onChange(stream.copy(recordingUrl = it)) }, R.string.field_stream_recording_url, R.string.field_stream_recording_url_hint, StreamSettings.Field.RECORDING_URL in errors)
    OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_save_stream))
    }
}

@Composable
private fun UrlField(value: String, onChange: (String) -> Unit, label: Int, hint: Int, isError: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(stringResource(label)) },
        isError = isError,
        supportingText = { Text(stringResource(if (isError) R.string.error_stream_url else hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
