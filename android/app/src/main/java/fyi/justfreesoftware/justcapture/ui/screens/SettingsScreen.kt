package fyi.justfreesoftware.justcapture.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.justfreesoftware.justcapture.AppGraph
import fyi.justfreesoftware.justcapture.config.EmailSettings
import fyi.justfreesoftware.justcapture.services.DriveStorageManager.StorageState
import fyi.justfreesoftware.justcapture.services.GmailService
import fyi.justfreesoftware.justcapture.ui.theme.AppColors
import fyi.justfreesoftware.justcapture.ui.theme.card
import fyi.justfreesoftware.justcapture.util.DeviceStorage
import fyi.justfreesoftware.justcapture.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

private sealed interface TestEmailState {
    data object Idle : TestEmailState
    data object Sending : TestEmailState
    data object Sent : TestEmailState
    data class Failed(val message: String) : TestEmailState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph) {
    val context = LocalContext.current
    val settings = graph.settings
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val userName by graph.authManager.userName.collectAsState()
    val userEmail by graph.authManager.userEmail.collectAsState()
    val userPhotoUrl by graph.authManager.userPhotoUrl.collectAsState()
    val publicSharingBlocked by graph.uploadQueueManager.publicSharingBlocked.collectAsState()
    val quota by graph.driveStorageManager.quota.collectAsState()
    val forcedOut by graph.driveStorageManager.forcedOutOfStorage.collectAsState()
    val driveState = graph.driveStorageManager.state(quota, forcedOut)

    var testEmailState by remember { mutableStateOf<TestEmailState>(TestEmailState.Idle) }
    var isRefreshingStorage by remember { mutableStateOf(false) }
    var deviceFreeBytes by remember { mutableStateOf(DeviceStorage.availableBytes(context)) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    val recipients = EmailSettings.parseRecipients(settings.emailRecipients)
    val invalidRecipients = recipients.filterNot { EmailSettings.isValidEmail(it) }
    val isDeviceStorageLow = deviceFreeBytes?.let {
        DeviceStorage.isLow(it, graph.driveStorageManager.bytesPerSecondEstimate())
    } ?: false

    LaunchedEffect(Unit) {
        val subject = settings.emailSubject
        if (subject.isEmpty() || subject == EmailSettings.LEGACY_SUBJECT) {
            settings.emailSubject = EmailSettings.DEFAULT_SUBJECT
        }
        graph.driveStorageManager.refreshIfStale()
    }

    fun sendTestEmail() {
        focusManager.clearFocus()
        val email = EmailSettings.from(settings)
        testEmailState = TestEmailState.Sending
        // App scope so leaving the tab doesn't cancel a send in flight.
        graph.appScope.launch {
            testEmailState = try {
                val token = graph.authManager.validAccessToken()
                GmailService.sendEmergencyEmail(
                    to = email.recipients,
                    subject = "[Test] " + EmailSettings.personalize(email.subject, userName, userEmail),
                    bodyNote = "This is a test from Just Capture. A real alert also includes a link to your live " +
                        "recording in Google Drive." + if (email.note.isEmpty()) "" else "\n\n${email.note}",
                    startedAtMs = if (email.includeTime) System.currentTimeMillis() else null,
                    address = null,
                    latitude = null,
                    longitude = null,
                    driveFolderLink = null,
                    accessToken = token
                )
                TestEmailState.Sent
            } catch (e: Exception) {
                TestEmailState.Failed("Couldn't send")
            }
            delay(4_000)
            if (testEmailState != TestEmailState.Sending) testEmailState = TestEmailState.Idle
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenTitle("Settings")
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // ---- Account ----
            Section(
                header = "Account",
                footer = if (publicSharingBlocked) {
                    {
                        FooterWarning(
                            "This account doesn't allow public links, so alert contacts won't be able to open your " +
                                "videos. This is usually a work or school account policy. Sign in with a personal " +
                                "Google account instead."
                        )
                    }
                } else null
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(userPhotoUrl, (userName ?: userEmail ?: "?").take(1).uppercase())
                    Column(modifier = Modifier.weight(1f)) {
                        Text(userName ?: "Google Account", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        userEmail?.let { Text(it, color = AppColors.secondaryText, fontSize = 12.sp) }
                    }
                }
                Divider()
                Text(
                    "Sign Out",
                    color = AppColors.record,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSignOutConfirm = true }
                        .padding(vertical = 4.dp)
                )
            }

            // ---- Recording ----
            Section(
                header = "Recording",
                footer = {
                    FooterText("Each clip uploads as soon as it ends. Shorter clips get footage off your phone sooner; longer clips use less data.")
                }
            ) {
                Text("Clip length", color = Color.White, fontSize = 16.sp)
                val options = listOf(10.0 to "10 sec", 30.0 to "30 sec", 60.0 to "60 sec")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = settings.chunkDurationSec == value,
                            onClick = { settings.chunkDurationSec = value },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = AppColors.accentContainer,
                                activeContentColor = AppColors.accent,
                                activeBorderColor = AppColors.stroke,
                                inactiveBorderColor = AppColors.stroke,
                                inactiveContainerColor = Color.Transparent,
                                inactiveContentColor = AppColors.secondaryText
                            )
                        ) { Text(label) }
                    }
                }
            }

            // ---- Email alerts ----
            Section(
                header = "Email Alerts",
                footer = {
                    when {
                        !settings.emailAlertsEnabled -> FooterText("No one will be notified when you start recording.")
                        invalidRecipients.isNotEmpty() -> FooterText(
                            "Check ${invalidRecipients.joinToString(", ")} — " +
                                "${if (invalidRecipients.size == 1) "it doesn't" else "they don't"} look like an email address.",
                            AppColors.record
                        )
                        recipients.isEmpty() -> FooterText(
                            "Add at least one person to email when you start recording. Separate addresses with commas.",
                            AppColors.warning
                        )
                        else -> FooterText(
                            "When you start recording, ${if (recipients.size == 1) "1 person gets" else "${recipients.size} people get"} " +
                                "an email with a link to the live recording in your Google Drive."
                        )
                    }
                }
            ) {
                SwitchRow("Email alerts", settings.emailAlertsEnabled) { settings.emailAlertsEnabled = it }
                if (settings.emailAlertsEnabled) {
                    OutlinedTextField(
                        value = settings.emailRecipients,
                        onValueChange = { settings.emailRecipients = it },
                        label = { Text("Recipients") },
                        placeholder = { Text("name@example.com, …") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth()
                    )
                    val canTest = recipients.isNotEmpty() && invalidRecipients.isEmpty() && testEmailState != TestEmailState.Sending
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (canTest) 1f else 0.4f)
                            .clickable(enabled = canTest, onClick = ::sendTestEmail)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
                        Text("Send Test Email", color = AppColors.accent, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        when (val state = testEmailState) {
                            TestEmailState.Idle -> {}
                            TestEmailState.Sending -> CircularProgressIndicator(color = AppColors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            TestEmailState.Sent -> StatusLabel("Sent", Icons.Filled.CheckCircle, AppColors.success)
                            is TestEmailState.Failed -> StatusLabel(state.message, Icons.Filled.Warning, AppColors.record)
                        }
                    }
                }
            }

            // ---- Email content ----
            if (settings.emailAlertsEnabled) {
                Section(
                    header = "Email Content",
                    footer = {
                        FooterText(
                            "${EmailSettings.NAME_TOKEN} in the subject is replaced with your name. A second email is " +
                                "sent if your Google Drive runs out of space mid-recording."
                        )
                    }
                ) {
                    OutlinedTextField(
                        value = settings.emailSubject,
                        onValueChange = { settings.emailSubject = it },
                        label = { Text("Subject") },
                        placeholder = { Text(EmailSettings.DEFAULT_SUBJECT) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = settings.emailNote,
                        onValueChange = { settings.emailNote = it },
                        label = { Text("Message (optional)") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow("Include start time", settings.includeTimeInEmail) { settings.includeTimeInEmail = it }
                    SwitchRow("Include location", settings.includeLocationInEmail) { settings.includeLocationInEmail = it }
                }
            }

            // ---- Storage ----
            Section(
                header = "Storage",
                footer = {
                    val parts = mutableListOf<String>()
                    graph.driveStorageManager.estimatedRecordingSeconds()?.let {
                        parts += "Enough space for about ${Formatters.recordingTime(it)} of recording."
                    }
                    parts += "Clips stay on this phone after uploading."
                    if (isDeviceStorageLow) {
                        parts += "Your phone is running low on space; delete old sessions in My Clips to make room for new recordings."
                    }
                    parts += "Clips in Google Drive are never deleted by the app."
                    FooterText(parts.joinToString(" "))
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Google Drive", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    val (text, color) = when (driveState) {
                        StorageState.OK, StorageState.LOW ->
                            (quota?.availableBytes?.let { "${Formatters.storageBytes(it)} free" } ?: "") to
                                (if (driveState == StorageState.LOW) AppColors.warning else AppColors.secondaryText)
                        StorageState.OUT -> "Full" to AppColors.record
                        StorageState.UNLIMITED -> "Unlimited" to AppColors.secondaryText
                        StorageState.UNAVAILABLE -> "Unavailable" to AppColors.secondaryText
                    }
                    Text(text, color = color, fontSize = 16.sp)
                }
                val q = quota
                val limit = q?.limit
                if (q != null && !q.isUnlimited && limit != null && limit > 0) {
                    LinearProgressIndicator(
                        progress = { (q.usage.toFloat() / limit).coerceIn(0f, 1f) },
                        color = if (driveState == StorageState.OK) AppColors.accent else AppColors.warning,
                        trackColor = AppColors.surfaceRaised,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isRefreshingStorage) {
                            isRefreshingStorage = true
                            deviceFreeBytes = DeviceStorage.availableBytes(context)
                            scope.launch {
                                graph.driveStorageManager.refresh()
                                isRefreshingStorage = false
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Refresh Storage", color = AppColors.accent, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (isRefreshingStorage) CircularProgressIndicator(color = AppColors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                deviceFreeBytes?.let { free ->
                    Divider()
                    Row {
                        Text("This phone", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(
                            "${Formatters.storageBytes(free)} free",
                            color = if (isDeviceStorageLow) AppColors.warning else AppColors.secondaryText,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // ---- About ----
            Section(
                header = "About",
                footer = { FooterText("Just Capture records continuously and backs every clip up to your Google Drive as it goes.") }
            ) {
                Row {
                    Text("Version", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(appVersion(context), color = AppColors.secondaryText, fontSize = 16.sp)
                }
            }
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text("Sign out of Google?") },
            text = { Text("Recording stops, and uploads and alert emails pause until you sign back in.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    graph.authManager.signOut()
                }) { Text("Sign Out", color = AppColors.record, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") } }
        )
    }
}

private fun appVersion(context: android.content.Context): String {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    @Suppress("DEPRECATION")
    val code = info?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 1
    return "${info?.versionName ?: "1.0"} ($code)"
}

@Composable
private fun Section(
    header: String,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            header,
            color = AppColors.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
        )
        Column(modifier = Modifier.card(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        if (footer != null) {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) { footer() }
        }
    }
}

@Composable
private fun FooterText(text: String, color: Color = AppColors.secondaryText) {
    Text(text, color = color, fontSize = 12.sp)
}

@Composable
private fun FooterWarning(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = AppColors.warning, modifier = Modifier.size(14.dp))
        Text(text, color = AppColors.warning, fontSize = 12.sp)
    }
}

@Composable
private fun StatusLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = AppColors.stroke)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The signed-in user's Google photo, or their initial on the brand gradient. */
@Composable
private fun Avatar(photoUrl: String?, initial: String) {
    val bitmap by produceState<Bitmap?>(null, photoUrl) {
        value = photoUrl?.let { url ->
            withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(AppColors.brandGradient),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(initial, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}
