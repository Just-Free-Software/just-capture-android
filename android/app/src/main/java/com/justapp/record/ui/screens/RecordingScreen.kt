package com.justapp.record.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.justapp.record.AppGraph
import com.justapp.record.model.RecordingSession
import com.justapp.record.services.DriveStorageManager
import com.justapp.record.services.GoogleDriveService
import com.justapp.record.ui.components.PulseIndicator
import com.justapp.record.util.Formatters
import com.justapp.record.util.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun RecordingScreen(graph: AppGraph) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraManager = graph.cameraManager
    val locationManager = graph.locationManager
    val driveStorageManager = graph.driveStorageManager
    val settings = graph.settings

    // ---- Permissions (camera + mic + location), requested once up front. ----

    fun allPermissionsGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    var cameraPermissionGranted by remember { mutableStateOf(allPermissionsGranted()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { cameraPermissionGranted = allPermissionsGranted() }

    // ---- Alert state. ----

    var showLowStorageConfirm by remember { mutableStateOf(false) }
    var showOutOfStorageAlert by remember { mutableStateOf(false) }
    var showQuotaExceededAlert by remember { mutableStateOf(false) }
    var showUploadsResumedAlert by remember { mutableStateOf(false) }
    var storageEmailSentForSessionId by remember { mutableStateOf<String?>(null) }
    var hadStorageExceeded by remember { mutableStateOf(false) }

    // ---- Actions (declared before the layout that references them). ----

    fun enqueueAlertEmailIfNeeded(session: RecordingSession) {
        if (!settings.emailAlertsEnabled) return

        val emails = settings.recipientsList()
        if (emails.isEmpty()) return

        val customSubject = settings.emailSubject.trim()
        val subject = if (customSubject.isNotEmpty() &&
            customSubject != "🚨 Emergency Recording Alert"
        ) {
            customSubject
        } else {
            "Alert! [your_name] is broadcasting"
        }
        val includeLocation = settings.includeLocationInEmail

        graph.emailQueueManager.enqueue(
            sessionId = session.id,
            recipients = emails,
            subject = subject,
            bodyNote = settings.emailNote,
            includeTime = settings.includeTimeInEmail,
            address = if (includeLocation) locationManager.currentAddress else null,
            latitude = if (includeLocation) locationManager.currentLatitude else null,
            longitude = if (includeLocation) locationManager.currentLongitude else null
        )
    }

    fun enqueueStorageFullEmailIfNeeded(session: RecordingSession) {
        if (!settings.emailAlertsEnabled) return

        val emails = settings.recipientsList()
        if (emails.isEmpty()) return

        val baseSubject = settings.emailSubject.trim()
        val subject = "Storage Full: ${baseSubject.ifEmpty { "Alert! [your_name] is broadcasting" }}"
        val includeLocation = settings.includeLocationInEmail

        val defaultNote =
            "Google Drive storage is full, so new clips cannot be uploaded right now. " +
                "Recording is still in progress and the app will keep retrying — clips will " +
                "upload automatically as soon as storage is freed."
        val customNote = settings.storageFullEmailNote.trim()
        val bodyNote = if (customNote.isEmpty()) defaultNote else customNote

        graph.emailQueueManager.enqueue(
            sessionId = session.id,
            recipients = emails,
            subject = subject,
            bodyNote = bodyNote,
            includeTime = settings.includeTimeInEmail,
            address = if (includeLocation) locationManager.currentAddress else null,
            latitude = if (includeLocation) locationManager.currentLatitude else null,
            longitude = if (includeLocation) locationManager.currentLongitude else null
        )
    }

    fun startRecording() {
        graph.uploadQueueManager.storageExceeded.value = false
        val duration = settings.chunkDurationSec
        val session = RecordingSession.new(duration)
        graph.appScope.launch { graph.sessionStore.add(session) }

        cameraManager.onChunkCompleted = { chunk ->
            graph.appScope.launch {
                graph.sessionStore.appendChunk(session.id, chunk)
                graph.uploadQueueManager.enqueue(session.id, chunk.id)
            }
        }

        locationManager.requestLocation()

        val outputDir = File(context.filesDir, "recordings/${session.id}")
        outputDir.mkdirs()
        cameraManager.startRecording(duration, outputDir)

        // Queue the emergency alert email (it will be retried automatically
        // by the email queue if the connection is poor). The Drive folder
        // link is resolved at send time, so the email still gets out ASAP
        // even if folder creation hasn't completed yet.
        enqueueAlertEmailIfNeeded(session)

        // Ensure the Drive folder is created early so the playlist can be
        // built and the folder link is ready for the email's next send
        // attempt. Retried with backoff; failures are non-fatal — the first
        // chunk upload will also retry folder creation.
        graph.appScope.launch {
            var attempt = 0
            while (true) {
                try {
                    val token = graph.authManager.validAccessToken()
                    val driveResult = GoogleDriveService.createFolder(
                        name = session.folderName,
                        accessToken = token
                    )
                    graph.sessionStore.update(session.id) {
                        it.copy(
                            driveFolderId = driveResult.first,
                            driveFolderLink = driveResult.second
                        )
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt += 1
                    if (attempt >= RetryPolicy.MAX_ATTEMPTS) {
                        return@launch
                    }
                    val delaySec = RetryPolicy.delayForAttempt(attempt)
                    delay((delaySec * 1000).toLong())
                }
            }
        }
    }

    fun attemptStartRecording() {
        scope.launch {
            driveStorageManager.refresh()
            when (driveStorageManager.state()) {
                DriveStorageManager.StorageState.OUT -> showOutOfStorageAlert = true
                DriveStorageManager.StorageState.LOW -> showLowStorageConfirm = true
                else -> startRecording()
            }
        }
    }

    fun toggleRecording() {
        if (cameraManager.isRecording) {
            cameraManager.stopRecording()
            graph.appScope.launch {
                val session = graph.sessionStore.finalizeActive()
                if (session != null) {
                    graph.uploadQueueManager.updatePlaylist(session.id)
                }
            }
        } else {
            attemptStartRecording()
        }
    }

    fun lowStorageMessage(): String {
        var message = "Your Google Drive storage is running low."
        val available = driveStorageManager.availableBytes()
        if (available != null) {
            message += " ${Formatters.formatBytes(context, available)} left"
            val seconds = driveStorageManager.estimatedRecordingSeconds()
            if (seconds != null) {
                message += " — about ${Formatters.formatRecordingTime(seconds)} of recording"
            }
            message += "."
        }
        message += " If it fills up mid-recording, new clips can't be uploaded."
        return message
    }

    // ---- Effects. ----

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(cameraPermissionGranted) {
        if (cameraPermissionGranted) {
            cameraManager.setupSession(lifecycleOwner)
        }
    }

    LaunchedEffect(Unit) {
        driveStorageManager.refreshIfStale()
    }

    val storageExceeded by graph.uploadQueueManager.storageExceeded.collectAsState()
    LaunchedEffect(storageExceeded) {
        if (storageExceeded) {
            if (!hadStorageExceeded) {
                hadStorageExceeded = true
                showQuotaExceededAlert = true
            }
            val session = graph.sessionStore.currentSession
            if (session != null && session.isActive &&
                storageEmailSentForSessionId != session.id
            ) {
                storageEmailSentForSessionId = session.id
                enqueueStorageFullEmailIfNeeded(session)
            }
        } else if (hadStorageExceeded) {
            hadStorageExceeded = false
            showUploadsResumedAlert = true
        }
    }

    // When the user switches away from the app, the camera session is torn
    // down by the lifecycle, so any further chunks would be empty. Stop
    // recording cleanly, finalize the session, and refresh the playlist
    // instead of spamming 0-byte chunks.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && cameraManager.isRecording) {
                cameraManager.stopRecording()
                graph.appScope.launch {
                    val session = graph.sessionStore.finalizeActive()
                    if (session != null) {
                        graph.uploadQueueManager.updatePlaylist(session.id)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- Layout. ----

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (cameraPermissionGranted) {
            AndroidView(
                factory = { cameraManager.previewView },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "Camera permission is required to record",
                color = Color.Gray,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                textAlign = TextAlign.Center
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top overlay: gradient + REC pill / chunk controls.
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x99000000),
                                1f to Color.Transparent
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (cameraManager.isRecording) {
                        Surface(
                            color = Color(0x66161616),
                            shape = RoundedCornerShape(50)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PulseIndicator()
                                Text(
                                    "REC",
                                    color = Color(0xFFEF5350),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = Formatters.formatElapsed(cameraManager.elapsedTimeSec),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = Color(0x66161616),
                                shape = CircleShape
                            ) {
                                IconButton(
                                    onClick = { cameraManager.cutChunkNow() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCut,
                                        contentDescription = "Cut chunk now",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Surface(
                                color = Color(0x66161616),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "Chunk ${cameraManager.currentChunkIndex + 1}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Bottom overlay: gradient + email capsule + camera controls.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color(0xCC000000)
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!cameraManager.isRecording) {
                    val recipientCount =
                        if (settings.emailAlertsEnabled) settings.recipientsList().size else 0
                    Surface(
                        color = Color(0x66161616),
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Email,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = emailNotificationText(recipientCount),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Torch (disabled on the front camera, which has none).
                    IconButton(
                        onClick = { cameraManager.toggleTorch() },
                        enabled = !cameraManager.isUsingFrontCamera,
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0x66161616), CircleShape)
                            .alpha(if (cameraManager.isUsingFrontCamera) 0.5f else 1f)
                    ) {
                        Icon(
                            imageVector = if (cameraManager.isTorchOn) {
                                Icons.Filled.FlashOn
                            } else {
                                Icons.Filled.FlashOff
                            },
                            contentDescription = "Toggle torch",
                            tint = if (cameraManager.isTorchOn) Color(0xFFFFEB3B) else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    RecordButton(
                        isRecording = cameraManager.isRecording,
                        onClick = { toggleRecording() }
                    )

                    IconButton(
                        onClick = { cameraManager.flipCamera() },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0x66161616), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FlipCameraAndroid,
                            contentDescription = "Flip camera",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }

    // ---- Alerts. ----

    if (showOutOfStorageAlert) {
        AlertDialog(
            onDismissRequest = { showOutOfStorageAlert = false },
            title = { Text("Google Drive Storage Full") },
            text = {
                Text(
                    "You're out of Google Drive storage, so new clips can't be uploaded. " +
                        "Free up space in your Drive or upgrade your storage plan, then try " +
                        "recording again."
                )
            },
            confirmButton = {
                TextButton(onClick = { showOutOfStorageAlert = false }) { Text("OK") }
            }
        )
    }

    if (showQuotaExceededAlert) {
        AlertDialog(
            onDismissRequest = { showQuotaExceededAlert = false },
            title = { Text("Uploads Paused — Google Drive Storage Full") },
            text = {
                Text(
                    "New clips can't be uploaded because your Google Drive storage is full. " +
                        "Recording continues on this device, and an alert email has been sent. " +
                        "Uploads will resume automatically as soon as space is freed."
                )
            },
            confirmButton = {
                TextButton(onClick = { showQuotaExceededAlert = false }) { Text("OK") }
            }
        )
    }

    if (showUploadsResumedAlert) {
        AlertDialog(
            onDismissRequest = { showUploadsResumedAlert = false },
            title = { Text("Uploads Resumed") },
            text = { Text("Google Drive storage is available again. Queued clips are uploading.") },
            confirmButton = {
                TextButton(onClick = { showUploadsResumedAlert = false }) { Text("OK") }
            }
        )
    }

    if (showLowStorageConfirm) {
        AlertDialog(
            onDismissRequest = { showLowStorageConfirm = false },
            title = { Text("Low Google Drive Storage") },
            text = { Text(lowStorageMessage()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLowStorageConfirm = false
                        startRecording()
                    }
                ) { Text("Record Anyway", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showLowStorageConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

private fun emailNotificationText(recipientCount: Int): String = when {
    recipientCount == 0 -> "Email notifications not set up"
    recipientCount == 1 -> "Recording will send emails to 1 person"
    else -> "Recording will send emails to $recipientCount people"
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    val innerSize by animateDpAsState(
        targetValue = if (isRecording) 32.dp else 64.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "recordButtonSize"
    )
    val corner by animateDpAsState(
        targetValue = if (isRecording) 8.dp else 32.dp,
        animationSpec = tween(durationMillis = 300),
        label = "recordButtonCorner"
    )

    Box(
        modifier = Modifier
            .size(76.dp)
            .border(3.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .background(Color(0xFFEF5350), RoundedCornerShape(corner))
        )
    }
}
