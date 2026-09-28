package info.soslive.stream.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.soslive.stream.R
import info.soslive.stream.auth.sso.GoogleSignIn
import info.soslive.stream.auth.sso.SimulatedSso
import info.soslive.stream.auth.sso.SsoProvider
import info.soslive.stream.auth.sso.SsoResult
import info.soslive.stream.auth.sso.rememberFacebookLogin
import info.soslive.stream.core.config.AppConfig
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.ui.common.findActivity
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onRegister: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthScaffold(error = state.error?.asString()) {
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text(stringResource(R.string.field_email)) },
            isError = state.emailError != null,
            supportingText = errorText(state.emailError),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(stringResource(R.string.field_password)) },
            isError = state.passwordError != null,
            supportingText = errorText(state.passwordError),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.login() }),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(stringResource(R.string.action_login), state.loading, viewModel::login)
        TextButton(onClick = onRegister) { Text(stringResource(R.string.action_go_register)) }

        SsoButtons(enabled = !state.loading, onResult = viewModel::onSsoResult)
    }
}

@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthScaffold(error = state.error?.asString()) {
        OutlinedTextField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = { Text(stringResource(R.string.field_display_name)) },
            isError = state.displayNameError != null,
            supportingText = errorText(state.displayNameError),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text(stringResource(R.string.field_email)) },
            isError = state.emailError != null,
            supportingText = errorText(state.emailError),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(stringResource(R.string.field_password)) },
            isError = state.passwordError != null,
            supportingText = {
                Text(state.passwordError?.asString() ?: stringResource(R.string.error_password_short, AuthViewModel.MIN_PASSWORD))
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.register() }),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(stringResource(R.string.action_register), state.loading, viewModel::register)
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_go_login)) }

        SsoButtons(enabled = !state.loading, onResult = viewModel::onSsoResult)
    }
}

private fun errorText(error: UiText?): (@Composable () -> Unit)? =
    if (error == null) null else { { Text(error.asString()) } }

@Composable
private fun AuthScaffold(error: String?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.logosos), contentDescription = stringResource(R.string.app_name), modifier = Modifier.height(96.dp))
        Spacer(Modifier.height(16.dp))
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        content()
    }
}

@Composable
private fun PrimaryButton(text: String, loading: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (loading) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp) else Text(text)
    }
}

@Composable
private fun SsoButtons(enabled: Boolean, onResult: (SsoProvider, SsoResult) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var simulatedProvider by remember { mutableStateOf<SsoProvider?>(null) }
    // Only touch the Facebook SDK when it was initialised (app id configured).
    val facebookLogin = if (AppConfig.facebookConfigured) rememberFacebookLogin { onResult(SsoProvider.FACEBOOK, it) } else null

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        HorizontalDivider(Modifier.weight(1f))
        Text(stringResource(R.string.or_continue_with), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
        HorizontalDivider(Modifier.weight(1f))
    }

    SsoButton(SsoProvider.GOOGLE, enabled) {
        if (SimulatedSso.isSimulated(SsoProvider.GOOGLE)) {
            simulatedProvider = SsoProvider.GOOGLE
        } else {
            val activity = context.findActivity() ?: return@SsoButton
            scope.launch { onResult(SsoProvider.GOOGLE, GoogleSignIn.requestIdToken(activity)) }
        }
    }
    SsoButton(SsoProvider.FACEBOOK, enabled) {
        if (facebookLogin == null) simulatedProvider = SsoProvider.FACEBOOK else facebookLogin()
    }

    simulatedProvider?.let { provider ->
        SimulatedSsoDialog(
            provider = provider,
            onDismiss = { simulatedProvider = null },
            onConfirm = { email, name ->
                simulatedProvider = null
                onResult(provider, SsoResult.Success(SimulatedSso.token(email, name)))
            },
        )
    }
}

@Composable
private fun SsoButton(provider: SsoProvider, enabled: Boolean, onClick: () -> Unit) {
    val label = when (provider) {
        SsoProvider.GOOGLE -> stringResource(R.string.action_google)
        SsoProvider.FACEBOOK -> stringResource(R.string.action_facebook)
    }
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(if (SimulatedSso.isSimulated(provider)) "$label ${stringResource(R.string.sso_simulated_suffix)}" else label)
    }
}

/** Stand-in for the provider's account picker when no client id is configured. */
@Composable
private fun SimulatedSsoDialog(provider: SsoProvider, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val valid = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.sso_simulated_title,
                    if (provider == SsoProvider.GOOGLE) "Google" else "Facebook",
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sso_simulated_body), style = MaterialTheme.typography.bodySmall)
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
        confirmButton = { TextButton(onClick = { onConfirm(email, name) }, enabled = valid) { Text(stringResource(R.string.action_login)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
