package info.soslive.stream.ui.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.soslive.stream.R
import info.soslive.stream.ui.common.findActivity

@Composable
fun SignInScreen(viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var simulatedDialog by remember { mutableStateOf(false) }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK, result.data)
    }
    LaunchedEffect(state.consent) {
        state.consent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.logosos), contentDescription = stringResource(R.string.app_name), modifier = Modifier.height(96.dp))

        // Why Drive: shown before Google's consent screen (required by the guide).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.drive_explain_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.drive_explain_body), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (state.driveDenied) {
            Text(stringResource(R.string.drive_denied), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        Button(
            onClick = {
                when {
                    viewModel.simulated -> simulatedDialog = true
                    state.driveDenied -> viewModel.retryDrive()
                    else -> context.findActivity()?.let(viewModel::signIn)
                }
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    when {
                        viewModel.simulated -> stringResource(R.string.action_sign_in_simulated)
                        state.driveDenied -> stringResource(R.string.action_allow_drive)
                        else -> stringResource(R.string.action_sign_in_google)
                    },
                )
            }
        }
        if (viewModel.simulated) {
            Text(stringResource(R.string.simulated_hint), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (simulatedDialog) {
        SimulatedAccountDialog(
            onDismiss = { simulatedDialog = false },
            onConfirm = { email, name ->
                simulatedDialog = false
                viewModel.signInSimulated(email, name)
            },
        )
    }
}

@Composable
private fun SimulatedAccountDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val valid = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_sign_in_simulated)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.field_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_display_name)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(email, name) }, enabled = valid) { Text(stringResource(R.string.action_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
