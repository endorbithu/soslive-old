package info.soslive.stream.ui.stream

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.soslive.stream.R
import info.soslive.stream.auth.EventType
import info.soslive.stream.stream.StreamController
import kotlinx.coroutines.launch
import java.io.File

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
private val OPTIONAL_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.SEND_SMS,
)

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamScreen(
    onOpenEvents: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: StreamViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ---- permissions
    var cameraGranted by remember { mutableStateOf(REQUIRED_PERMISSIONS.all(context::isGranted)) }
    val locationSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted = REQUIRED_PERMISSIONS.all { result[it] == true || context.isGranted(it) }
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            scope.launch {
                viewModel.locationSettingsResolution()?.let {
                    locationSettingsLauncher.launch(IntentSenderRequest.Builder(it.resolution).build())
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        val missing = (REQUIRED_PERMISSIONS + OPTIONAL_PERMISSIONS).filterNot(context::isGranted)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            viewModel.locationSettingsResolution()?.let {
                locationSettingsLauncher.launch(IntentSenderRequest.Builder(it.resolution).build())
            }
        }
    }

    // ---- camera / RTMP
    val controller = remember(cameraGranted) { if (cameraGranted) StreamController(context, viewModel) else null }
    DisposableEffect(controller) { onDispose { controller?.release() } }
    var flashOn by remember { mutableStateOf(false) }

    // ---- photo capture (system camera app)
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPhotoEventId by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingPhotoPath?.let(::File)
        val eventId = pendingPhotoEventId
        if (file != null && eventId != null) {
            if (success) viewModel.onPhotoCaptured(eventId, file) else viewModel.onPhotoCancelled(file)
        }
        pendingPhotoPath = null
        pendingPhotoEventId = null
    }

    // ---- one-off effects from the ViewModel
    LaunchedEffect(controller) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is StreamEffect.StartPublishing ->
                    if (controller == null || !controller.startStream(effect.publishUrl)) viewModel.onEncoderUnavailable()
                StreamEffect.StopPublishing -> {
                    controller?.stopStream()
                    flashOn = false
                }
                is StreamEffect.OpenComposers -> try {
                    // startActivities: the last intent (SMS) is on top, e-mail comes after it.
                    context.startActivities(effect.intents.toTypedArray())
                } catch (_: ActivityNotFoundException) {
                    snackbar.showSnackbar(context.getString(R.string.composer_no_app))
                }
                is StreamEffect.TakePhoto -> {
                    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
                    val file = File.createTempFile("incident_", ".jpg", dir)
                    pendingPhotoPath = file.absolutePath
                    pendingPhotoEventId = effect.eventFileId
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    try {
                        takePicture.launch(uri)
                    } catch (_: ActivityNotFoundException) {
                        viewModel.onPhotoCancelled(file)
                        snackbar.showSnackbar(context.getString(R.string.photo_no_camera_app))
                    }
                }
                is StreamEffect.Message -> {
                    // Short messages as Toast so they are not queued behind each other.
                    Toast.makeText(context, effect.text.asString(context), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var confirmStop by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(state.activeEvent?.title ?: stringResource(R.string.app_name), maxLines = 1)
                },
                actions = {
                    IconButton(onClick = onOpenEvents) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.menu_my_events))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.menu_settings))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_new_incident)) },
                                enabled = state.phase == LivePhase.IDLE && state.activeEvent != null,
                                onClick = {
                                    menuOpen = false
                                    viewModel.newIncident()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            if (controller != null) {
                AndroidView(factory = { controller.view }, modifier = Modifier.fillMaxSize())
            } else {
                PermissionMissing(onRequest = { permissionLauncher.launch(REQUIRED_PERMISSIONS) })
            }

            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp)) {
                LiveBadge(state.phase)
                if (state.activeEvent != null) {
                    val active = state.activeEvent!!
                    MessagesPanel(
                        link = active.link,
                        messages = state.messages,
                        expanded = state.messagesExpanded,
                        draft = state.messageDraft,
                        sending = state.sendingMessage,
                        onToggle = viewModel::toggleMessages,
                        onDraftChange = viewModel::onMessageDraftChange,
                        onSend = viewModel::sendMessage,
                    )
                }
            }

            ControlsBar(
                state = state,
                cameraReady = controller != null,
                flashOn = flashOn,
                onSos = { viewModel.startLive(EventType.SOS) },
                onLive = { viewModel.startLive(EventType.LIVE) },
                onPhoto = viewModel::takePhoto,
                onStop = { confirmStop = true },
                onSwitchCamera = { controller?.switchCamera() },
                onToggleFlash = {
                    when (val result = controller?.toggleFlash()) {
                        null -> Toast.makeText(context, R.string.flash_unavailable, Toast.LENGTH_SHORT).show()
                        else -> flashOn = result
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.stop_confirm_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    viewModel.stopLive()
                }) { Text(stringResource(R.string.action_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PermissionMissing(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.permission_camera_needed), color = Color.White, textAlign = TextAlign.Center)
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.action_grant)) }
    }
}

@Composable
private fun LiveBadge(phase: LivePhase) {
    val (text, color) = when (phase) {
        LivePhase.IDLE -> return
        LivePhase.CREATING_EVENT, LivePhase.CONNECTING -> stringResource(R.string.stream_connecting) to Color(0xFFFFA000)
        LivePhase.LIVE -> stringResource(R.string.stream_live) to Color(0xFFD32F2F)
        LivePhase.STOPPING -> stringResource(R.string.stream_stopping) to Color.Gray
    }
    Surface(color = color, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(bottom = 8.dp)) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun ControlsBar(
    state: StreamUiState,
    cameraReady: Boolean,
    flashOn: Boolean,
    onSos: () -> Unit,
    onLive: () -> Unit,
    onPhoto: () -> Unit,
    onStop: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleFlash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            FilledTonalIconButton(onClick = onSwitchCamera, enabled = cameraReady) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = stringResource(R.string.action_switch_camera))
            }
            FilledTonalIconButton(onClick = onToggleFlash, enabled = cameraReady) {
                Icon(if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff, contentDescription = stringResource(R.string.action_flash))
            }
        }

        when (state.phase) {
            LivePhase.IDLE -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                FilledTonalIconButton(onClick = onLive, enabled = cameraReady, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Filled.Videocam, contentDescription = stringResource(R.string.action_live))
                }
                SosButton(onClick = onSos, enabled = cameraReady)
                FilledTonalIconButton(
                    onClick = onPhoto,
                    enabled = !state.preparingPhoto && !state.uploadingPhoto,
                    modifier = Modifier.size(64.dp),
                ) {
                    when {
                        state.preparingPhoto || state.uploadingPhoto -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        state.activeEvent != null -> Icon(Icons.Filled.AddAPhoto, contentDescription = stringResource(R.string.action_add_photo))
                        else -> Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.action_photo))
                    }
                }
            }
            LivePhase.CREATING_EVENT, LivePhase.STOPPING -> CircularProgressIndicator(color = Color.White)
            LivePhase.CONNECTING, LivePhase.LIVE -> Button(
                onClick = onStop,
                shape = CircleShape,
                modifier = Modifier.size(96.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFD32F2F)),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.action_stop), modifier = Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun SosButton(onClick: () -> Unit, enabled: Boolean) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(112.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
    ) {
        Text(stringResource(R.string.action_sos), fontSize = 28.sp, fontWeight = FontWeight.Black)
    }
}
