package fyi.justfreesoftware.justcapture.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import fyi.justfreesoftware.justcapture.AppGraph
import fyi.justfreesoftware.justcapture.config.EmailSettings
import fyi.justfreesoftware.justcapture.model.EmailJob
import fyi.justfreesoftware.justcapture.model.EmailStatus
import fyi.justfreesoftware.justcapture.model.RecordingSession
import fyi.justfreesoftware.justcapture.services.DriveStorageManager.StorageState
import fyi.justfreesoftware.justcapture.startRecording
import fyi.justfreesoftware.justcapture.ui.components.PulseIndicator
import fyi.justfreesoftware.justcapture.ui.theme.AppColors
import fyi.justfreesoftware.justcapture.ui.theme.NoticeBanner
import fyi.justfreesoftware.justcapture.ui.theme.NoticeStyle
import fyi.justfreesoftware.justcapture.ui.theme.overlayPill
import fyi.justfreesoftware.justcapture.util.DeviceStorage
import fyi.justfreesoftware.justcapture.util.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CONTROL_SIZE = 54

fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun RecordingScreen(graph: AppGraph) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val camera = graph.cameraManager
    val location = graph.locationManager
    val driveStorage = graph.driveStorageManager
    val settings = graph.settings

    val sessions by graph.sessionStore.sessions.collectAsState()
    val emailJobs by graph.emailQueueManager.jobs.collectAsState()
    val storageExceeded by graph.uploadQueueManager.storageExceeded.collectAsState()
    val publicSharingBlocked by graph.uploadQueueManager.publicSharingBlocked.collectAsState()
    val quota by driveStorage.quota.collectAsState()
    val forcedOut by driveStorage.forcedOutOfStorage.collectAsState()
    val driveState = driveStorage.state(quota, forcedOut)

    // ---- Permissions: camera, mic and location, asked once up front. ----

    fun cameraGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    var cameraPermissionGranted by remember { mutableStateOf(cameraGranted()) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraPermissionGranted = cameraGranted()
        permissionDenied = !cameraPermissionGranted
        location.permissionAsked = true
        location.refreshPermission()
    }

    LaunchedEffect(Unit) {
        val needed = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else location.permissionAsked = true
    }

    LaunchedEffect(cameraPermissionGranted) {
        if (cameraPermissionGranted) camera.setupSession(lifecycleOwner)
    }

    // Local filesystem query only (no network), re-checked so the warning
    // appears if the phone fills up mid-recording.
    var deviceFreeBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        launch { driveStorage.refreshIfStale() }
        while (true) {
            deviceFreeBytes = DeviceStorage.availableBytes(context)
            delay(30_000)
        }
    }

    // Auto-lock would background the app, which releases the camera and
    // ends the recording, so the screen stays on while recording.
    val view = LocalView.current
    DisposableEffect(camera.isRecording) {
        view.keepScreenOn = camera.isRecording
        onDispose { view.keepScreenOn = false }
    }

    var showLowStorageConfirm by remember { mutableStateOf(false) }
    var showOutOfStorageAlert by remember { mutableStateOf(false) }

    val recipientCount = if (settings.emailAlertsEnabled) EmailSettings.parseRecipients(settings.emailRecipients).size else 0
    val liveSession = sessions.firstOrNull { it.isActive }?.takeIf { camera.isRecording }
    val liveEmailJob = liveSession?.let { live -> emailJobs.firstOrNull { it.sessionId == live.id } }
    val isDeviceStorageLow = deviceFreeBytes?.let {
        DeviceStorage.isLow(it, driveStorage.bytesPerSecondEstimate())
    } ?: false

    val haptics = LocalHapticFeedback.current
    fun toggleRecording() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (camera.isRecording) {
            // AppGraph finalizes the session when isRecording flips.
            camera.stopRecording()
        } else {
            scope.launch {
                driveStorage.refresh()
                when (driveStorage.state()) {
                    StorageState.OUT -> showOutOfStorageAlert = true
                    StorageState.LOW -> showLowStorageConfirm = true
                    else -> graph.startRecording()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background)
    ) {
        if (cameraPermissionGranted) {
            AndroidView(factory = { camera.previewView }, modifier = Modifier.fillMaxSize())
        } else if (permissionDenied) {
            CameraPermissionPlaceholder(Modifier.align(Alignment.Center)) { openAppSettings(context) }
        }

        // Scrims so overlays stay legible on a bright scene.
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (camera.isRecording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .overlayPill()
                            .semantics { contentDescription = "Recording, ${Formatters.duration(camera.elapsedTimeSec)}" },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PulseIndicator()
                        Text(
                            Formatters.duration(camera.elapsedTimeSec),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    liveSession?.let { UploadPill(it, storageExceeded) }
                }
            } else {
                ReadinessPills(
                    recipientCount = recipientCount,
                    driveState = driveState,
                    driveAvailable = quota?.availableBytes,
                    deviceLow = isDeviceStorageLow,
                    locationOn = location.permissionGranted
                )
            }

            // Banners scroll if several stack up on a small screen.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                camera.recordingError?.let { error ->
                    NoticeBanner(
                        style = NoticeStyle.ERROR,
                        icon = Icons.Filled.VideocamOff,
                        title = "Recording stopped",
                        message = "$error Clips recorded so far are saved and still uploading.",
                        onDismiss = { camera.recordingError = null }
                    )
                }
                if (storageExceeded) {
                    NoticeBanner(
                        style = NoticeStyle.WARNING,
                        icon = Icons.Filled.CloudOff,
                        title = "Google Drive is full",
                        message = "Clips are kept on this phone and will upload automatically once you free up space."
                    )
                }
                val free = deviceFreeBytes
                if (isDeviceStorageLow && free != null) {
                    val minutes = Formatters.recordingTime(
                        DeviceStorage.recordingSeconds(free, driveStorage.bytesPerSecondEstimate())
                    )
                    NoticeBanner(
                        style = NoticeStyle.WARNING,
                        icon = Icons.Filled.PhoneAndroid,
                        title = "Phone storage is low",
                        message = "${Formatters.storageBytes(free)} free, about $minutes of recording. Clips are kept on " +
                            "this phone, so delete old sessions in My Clips to free up space."
                    )
                }
                if (publicSharingBlocked && recipientCount > 0) {
                    NoticeBanner(
                        style = NoticeStyle.WARNING,
                        icon = Icons.Filled.LinkOff,
                        title = "Contacts can't open your videos",
                        message = "Your Google account (likely work or school) doesn't allow public links, so alert " +
                            "emails will link to a private folder. Sign in with a personal Google account to fix this."
                    )
                }
                if (!camera.isRecording && !location.permissionGranted && location.permissionAsked) {
                    NoticeBanner(
                        style = NoticeStyle.INFO,
                        icon = Icons.Filled.LocationOff,
                        title = "Location is off",
                        message = "Alert emails won't include where you are.",
                        actionTitle = "Turn On in Settings",
                        onAction = { openAppSettings(context) }
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            BottomControls(
                graph = graph,
                liveEmailJob = liveEmailJob,
                cameraUnavailable = !cameraPermissionGranted,
                onToggleRecording = ::toggleRecording
            )
        }
    }

    if (showOutOfStorageAlert) {
        AlertDialog(
            onDismissRequest = { showOutOfStorageAlert = false },
            title = { Text("Google Drive Storage Full") },
            text = {
                Text(
                    "You're out of Google Drive storage, so new clips can't be uploaded. Free up space in your " +
                        "Drive or upgrade your storage plan, then try recording again."
                )
            },
            confirmButton = { TextButton(onClick = { showOutOfStorageAlert = false }) { Text("OK") } }
        )
    }

    if (showLowStorageConfirm) {
        AlertDialog(
            onDismissRequest = { showLowStorageConfirm = false },
            title = { Text("Low Google Drive Storage") },
            text = { Text(lowStorageMessage(graph)) },
            confirmButton = {
                TextButton(onClick = {
                    showLowStorageConfirm = false
                    graph.startRecording()
                }) { Text("Record Anyway", color = AppColors.record, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showLowStorageConfirm = false }) { Text("Cancel") } }
        )
    }
}

private fun lowStorageMessage(graph: AppGraph): String {
    var message = "Your Google Drive storage is running low."
    graph.driveStorageManager.availableBytes()?.let { available ->
        message += " ${Formatters.storageBytes(available)} left"
        graph.driveStorageManager.estimatedRecordingSeconds()?.let {
            message += " — about ${Formatters.recordingTime(it)} of recording"
        }
        message += "."
    }
    return "$message If it fills up mid-recording, new clips can't be uploaded."
}

@Composable
private fun ReadinessPills(
    recipientCount: Int,
    driveState: StorageState,
    driveAvailable: Long?,
    deviceLow: Boolean,
    locationOn: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        if (recipientCount > 0) {
            ReadinessPill(Icons.Filled.Email, "$recipientCount contact${if (recipientCount == 1) "" else "s"}", Color.White)
        } else {
            ReadinessPill(Icons.Filled.NotificationsOff, "No alerts", AppColors.warning)
        }
        val (driveText, driveColor) = when (driveState) {
            StorageState.OK -> (driveAvailable?.let { Formatters.storageBytes(it) } ?: "Drive") to Color.White
            StorageState.LOW -> "Drive low" to AppColors.warning
            StorageState.OUT -> "Drive full" to AppColors.record
            StorageState.UNLIMITED -> "Unlimited" to Color.White
            StorageState.UNAVAILABLE -> "Drive" to Color.White
        }
        ReadinessPill(Icons.Filled.Storage, driveText, driveColor)
        if (deviceLow) ReadinessPill(Icons.Filled.PhoneAndroid, "Phone low", AppColors.warning)
        if (locationOn) {
            ReadinessPill(Icons.Filled.LocationOn, "Location", Color.White)
        } else {
            ReadinessPill(Icons.Filled.LocationOff, "No location", AppColors.record)
        }
    }
}

@Composable
private fun ReadinessPill(icon: ImageVector, text: String, color: Color) {
    Row(
        modifier = Modifier.overlayPill(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun UploadPill(session: RecordingSession, storageExceeded: Boolean) {
    val queued = session.queuedCount
    val color = when {
        storageExceeded -> AppColors.record
        queued > 1 -> AppColors.warning
        else -> Color.White
    }
    val icon = when {
        storageExceeded -> Icons.Filled.CloudOff
        queued > 0 -> Icons.Filled.CloudUpload
        else -> Icons.Filled.CloudDone
    }
    Row(
        modifier = Modifier
            .overlayPill()
            .semantics { contentDescription = "${session.uploadedCount} of ${session.totalCount} clips uploaded" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(
            "${session.uploadedCount}/${session.totalCount} up",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun BottomControls(
    graph: AppGraph,
    liveEmailJob: EmailJob?,
    cameraUnavailable: Boolean,
    onToggleRecording: () -> Unit
) {
    val camera = graph.cameraManager
    val settings = graph.settings
    var tipDismissed by remember { mutableStateOf(false) }
    var tipDontShowAgain by remember { mutableStateOf(false) }
    val showTip = !settings.hideSendClipTip && !tipDismissed && !cameraUnavailable

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (camera.isRecording) {
            liveEmailJob?.let { EmailStatusLine(it) }
        } else {
            Text(
                "Clips upload every ${settings.chunkDurationSec.toInt()}s",
                color = AppColors.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            // Send clip now floats above the torch without changing the row's
            // layout; its tip floats above it.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp)
                    .offset(y = (-(CONTROL_SIZE + 14)).dp)
            ) {
                AnimatedVisibility(visible = showTip, enter = fadeIn(), exit = fadeOut()) {
                    SendClipTip(
                        chunkSeconds = settings.chunkDurationSec.toInt(),
                        dontShowAgain = tipDontShowAgain,
                        onToggleDontShowAgain = { tipDontShowAgain = !tipDontShowAgain },
                        onClose = {
                            if (tipDontShowAgain) settings.hideSendClipTip = true
                            tipDismissed = true
                        }
                    )
                }
                CircleControl(
                    icon = Icons.AutoMirrored.Filled.Send,
                    tint = Color.White,
                    label = if (camera.isRecording) {
                        "Send clip now. Ends clip ${camera.currentChunkIndex + 1} early and uploads it"
                    } else "Send clip now, available while recording",
                    enabled = camera.isRecording,
                    onClick = camera::cutChunkNow
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleControl(
                    icon = if (camera.isTorchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                    tint = if (camera.isTorchOn) Color(0xFFFFEB3B) else Color.White,
                    label = if (camera.isTorchOn) "Turn torch off" else "Turn torch on",
                    enabled = !camera.isUsingFrontCamera && !cameraUnavailable,
                    onClick = camera::toggleTorch
                )
                Spacer(Modifier.weight(1f))
                RecordButton(
                    isRecording = camera.isRecording,
                    enabled = !camera.isFinishing && !cameraUnavailable,
                    onClick = onToggleRecording
                )
                Spacer(Modifier.weight(1f))
                CircleControl(
                    icon = Icons.Filled.Cameraswitch,
                    tint = Color.White,
                    label = "Switch camera",
                    enabled = !cameraUnavailable,
                    onClick = camera::flipCamera
                )
            }
        }
    }
}

@Composable
private fun SendClipTip(
    chunkSeconds: Int,
    dontShowAgain: Boolean,
    onToggleDontShowAgain: () -> Unit,
    onClose: () -> Unit
) {
    val background = Color.Black.copy(alpha = 0.8f)
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Column(
            modifier = Modifier
                .width(240.dp)
                .background(background, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send clip now", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close tip",
                    tint = AppColors.secondaryText,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onClose)
                        .padding(6.dp)
                )
            }
            Text(
                "While recording, tap this to end the current clip early and upload it right away, instead of " +
                    "waiting for the $chunkSeconds-second clip to finish.",
                color = AppColors.secondaryText,
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier.clickable(onClick = onToggleDontShowAgain),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    if (dontShowAgain) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (dontShowAgain) AppColors.accent else Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Don't show again",
                    color = if (dontShowAgain) AppColors.accent else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        // Points down at the center of the send button.
        Text(
            "▼",
            color = background,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = (CONTROL_SIZE / 2 - 6).dp).offset(y = (-4).dp)
        )
    }
}

@Composable
private fun EmailStatusLine(job: EmailJob) {
    val (text, icon, color) = when (job.status) {
        EmailStatus.SENT -> Triple("Alert sent to ${job.recipients.size}", Icons.Filled.CheckCircle, AppColors.success)
        EmailStatus.SENDING -> Triple("Sending alert…", Icons.AutoMirrored.Filled.Send, Color.White)
        EmailStatus.PENDING -> Triple(
            if (job.attempts == 0) "Alert queued" else "Alert retrying (no connection?)",
            Icons.Filled.Schedule,
            AppColors.warning
        )
        EmailStatus.FAILED -> Triple("Alert failed — retry from My Clips", Icons.Filled.Warning, AppColors.record)
    }
    Row(
        modifier = Modifier.overlayPill(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CircleControl(icon: ImageVector, tint: Color, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(CONTROL_SIZE.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(AppColors.overlay, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val spec = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    val innerSize by animateDpAsState(if (isRecording) 32.dp else 68.dp, spec, label = "recordSize")
    val corner by animateDpAsState(if (isRecording) 8.dp else 34.dp, spec, label = "recordCorner")

    Box(
        modifier = Modifier
            .size(82.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (isRecording) "Stop recording" else "Start recording" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .background(AppColors.record, RoundedCornerShape(corner))
        )
    }
}

@Composable
private fun CameraPermissionPlaceholder(modifier: Modifier, onOpenSettings: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = AppColors.secondaryText, modifier = Modifier.size(44.dp))
        Text("Camera access needed", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Just Capture needs the camera and microphone to record. You can turn them on in Settings.",
            color = AppColors.secondaryText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
        ) { Text("Open Settings") }
    }
}
