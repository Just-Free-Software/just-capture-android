package fyi.justfreesoftware.justcapture.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.justfreesoftware.justcapture.AppGraph
import fyi.justfreesoftware.justcapture.services.DriveStorageManager
import fyi.justfreesoftware.justcapture.util.Formatters
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(graph: AppGraph) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = graph.settings
    val scope = rememberCoroutineScope()

    val isSignedIn by graph.authManager.isSignedIn.collectAsState()
    val userName by graph.authManager.userName.collectAsState()
    val userEmail by graph.authManager.userEmail.collectAsState()

    // Local editable state, initialized from settings; every edit writes
    // through immediately (like the iOS app's @AppStorage bindings).
    var chunkDurationSec by remember { mutableStateOf(settings.chunkDurationSec) }
    var emailRecipients by remember { mutableStateOf(settings.emailRecipients) }
    var emailSubject by remember { mutableStateOf(settings.emailSubject) }
    var emailNote by remember { mutableStateOf(settings.emailNote) }
    var storageFullEmailNote by remember { mutableStateOf(settings.storageFullEmailNote) }
    var emailAlertsEnabled by remember { mutableStateOf(settings.emailAlertsEnabled) }
    var includeTimeInEmail by remember { mutableStateOf(settings.includeTimeInEmail) }
    var includeLocationInEmail by remember { mutableStateOf(settings.includeLocationInEmail) }
    var saveChunksLocally by remember { mutableStateOf(settings.saveChunksLocally) }

    val quota by graph.driveStorageManager.quota.collectAsState()
    val forcedOutOfStorage by graph.driveStorageManager.forcedOutOfStorage.collectAsState()
    val sessions by graph.sessionStore.sessions.collectAsState()

    LaunchedEffect(Unit) {
        if (emailSubject.isEmpty() || emailSubject == "🚨 Emergency Recording Alert") {
            emailSubject = "Alert! [your_name] is broadcasting"
        }
        if (isSignedIn) {
            graph.driveStorageManager.refreshIfStale()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            "Settings",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        // ---- Account ----
        SectionCard(title = "Account") {
            if (isSignedIn) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFF2196F3), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val initial = (
                            userName?.firstOrNull()?.uppercaseChar()
                                ?: userEmail?.firstOrNull()?.uppercaseChar()
                                ?: '?'
                            ).toString()
                        Text(initial, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = userName ?: "User",
                            color = Color.White
                        )
                        Text(
                            text = userEmail ?: "",
                            color = Color(0xFFAAAAAA),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { graph.authManager.signOut() }) {
                        Text("Sign Out", color = Color(0xFFEF5350))
                    }
                }
            } else {
                Button(onClick = {}) { Text("Sign In") }
            }
        }

        // ---- Recording ----
        SectionCard(title = "Recording") {
            Text("Upload a clip every", color = Color(0xFFAAAAAA))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(10.0 to "10s", 30.0 to "30s", 60.0 to "60s").forEach { (value, label) ->
                    FilterChip(
                        selected = chunkDurationSec == value,
                        onClick = {
                            chunkDurationSec = value
                            settings.chunkDurationSec = value
                        },
                        label = { Text(label) }
                    )
                }
            }
        }

        // ---- Email Alerts ----
        SectionCard(title = "Email Alerts") {
            SettingSwitch("Enable Alerts", emailAlertsEnabled) {
                emailAlertsEnabled = it
                settings.emailAlertsEnabled = it
            }

            if (emailAlertsEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emailRecipients,
                    onValueChange = {
                        emailRecipients = it
                        settings.emailRecipients = it
                    },
                    label = { Text("comma-separated emails") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Email
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = darkTextFieldColors()
                )
                Text(
                    "Enter recipient email addresses, separated by commas",
                    color = Color(0xFFAAAAAA),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emailSubject,
                    onValueChange = {
                        emailSubject = it
                        settings.emailSubject = it
                    },
                    label = { Text("Subject") },
                    placeholder = { Text("Alert! [your_name] is broadcasting") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = darkTextFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emailNote,
                    onValueChange = {
                        emailNote = it
                        settings.emailNote = it
                    },
                    label = { Text("Optional note to include") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    colors = darkTextFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = storageFullEmailNote,
                    onValueChange = {
                        storageFullEmailNote = it
                        settings.storageFullEmailNote = it
                    },
                    label = { Text("Storage full note") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    colors = darkTextFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                SettingSwitch("Include Time", includeTimeInEmail) {
                    includeTimeInEmail = it
                    settings.includeTimeInEmail = it
                }
                SettingSwitch("Include Location", includeLocationInEmail) {
                    includeLocationInEmail = it
                    settings.includeLocationInEmail = it
                }
            }
        }

        // ---- Storage ----
        SectionCard(title = "Storage") {
            if (isSignedIn) {
                val driveState = graph.driveStorageManager.state()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Google Drive", color = Color.White, modifier = Modifier.weight(1f))
                    val statusColor = when (driveState) {
                        DriveStorageManager.StorageState.OK -> Color(0xFFAAAAAA)
                        DriveStorageManager.StorageState.LOW -> Color(0xFFFFC107)
                        DriveStorageManager.StorageState.OUT -> Color(0xFFEF5350)
                        else -> Color(0xFFAAAAAA)
                    }
                    val available = graph.driveStorageManager.availableBytes()
                    val statusText = when (driveState) {
                        DriveStorageManager.StorageState.OK ->
                            available?.let { Formatters.formatBytes(context, it) } ?: ""
                        DriveStorageManager.StorageState.LOW ->
                            available?.let { Formatters.formatBytes(context, it) + " left" } ?: ""
                        DriveStorageManager.StorageState.OUT -> "Full"
                        DriveStorageManager.StorageState.UNLIMITED -> "Unlimited"
                        DriveStorageManager.StorageState.UNAVAILABLE -> "Unavailable"
                    }
                    Text(statusText, color = statusColor)
                }

                val q = quota
                if (q != null && !q.isUnlimited) {
                    Text(
                        "${Formatters.formatBytes(context, q.availableBytes ?: 0)} free of " +
                            Formatters.formatBytes(context, q.limit ?: 0),
                        color = Color(0xFFAAAAAA),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                val estimatedSeconds = remember(sessions, quota, forcedOutOfStorage) {
                    graph.driveStorageManager.estimatedRecordingSeconds()
                }
                if (estimatedSeconds != null) {
                    Text(
                        "That's about ${Formatters.formatRecordingTime(estimatedSeconds)} of recording time left",
                        color = Color(0xFFAAAAAA),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (driveState == DriveStorageManager.StorageState.UNLIMITED) {
                    Text(
                        "Unlimited storage — record as long as you like",
                        color = Color(0xFFAAAAAA),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { scope.launch { graph.driveStorageManager.refresh() } }) {
                        Text("Refresh", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SettingSwitch("Save Chunks Locally", saveChunksLocally) {
                saveChunksLocally = it
                settings.saveChunksLocally = it
            }
            Text(
                "Keep recorded clips on device after upload. When off, local files are deleted " +
                    "once they're successfully sent; chunks are still written to disk until then " +
                    "so uploads can complete.",
                color = Color(0xFFAAAAAA),
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF42A5F5),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Clips sent to Google Drive will NOT be deleted.",
                    color = Color(0xFF42A5F5),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ---- About ----
        SectionCard(title = "About") {
            Row {
                Text("Just App 2", color = Color.White, modifier = Modifier.weight(1f))
                Text("v1.0", color = Color(0xFFAAAAAA))
            }
            Text(
                "Continuous video recording with Google Drive upload",
                color = Color(0xFFAAAAAA),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x0DFFFFFF), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            title,
            color = Color(0xFFAAAAAA),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun darkTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color(0xFF42A5F5),
    unfocusedBorderColor = Color(0xFF666666),
    focusedLabelColor = Color(0xFF42A5F5),
    unfocusedLabelColor = Color(0xFFAAAAAA),
    cursorColor = Color(0xFF42A5F5)
)
