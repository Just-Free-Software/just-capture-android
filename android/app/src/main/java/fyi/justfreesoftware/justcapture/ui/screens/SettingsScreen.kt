package fyi.justfreesoftware.justcapture.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.justfreesoftware.justcapture.AppGraph
import fyi.justfreesoftware.justcapture.config.AppConfig
import fyi.justfreesoftware.justcapture.config.EmailSettings
import fyi.justfreesoftware.justcapture.services.DriveStorageManager.StorageState
import fyi.justfreesoftware.justcapture.services.GmailService
import fyi.justfreesoftware.justcapture.ui.theme.AppColors
import fyi.justfreesoftware.justcapture.ui.theme.card
import fyi.justfreesoftware.justcapture.util.DeviceStorage
import fyi.justfreesoftware.justcapture.util.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface TestEmailState {
    data object Idle : TestEmailState
    data object Sending : TestEmailState
    data object Sent : TestEmailState
    data class Failed(val message: String) : TestEmailState
}

/**
 * [scrollTarget] is set by another tab to scroll here to one setting;
 * [onScrolledToTarget] clears it once shown.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(graph: AppGraph, scrollTarget: SettingsSection? = null, onScrolledToTarget: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = graph.settings
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val userEmail by graph.authManager.userEmail.collectAsState()
    val publicSharingBlocked by graph.uploadQueueManager.publicSharingBlocked.collectAsState()
    val quota by graph.driveStorageManager.quota.collectAsState()
    val forcedOut by graph.driveStorageManager.forcedOutOfStorage.collectAsState()
    val driveState = graph.driveStorageManager.state(quota, forcedOut)

    var testEmailState by remember { mutableStateOf<TestEmailState>(TestEmailState.Idle) }
    var isRefreshingStorage by remember { mutableStateOf(false) }
    var deviceFreeBytes by remember { mutableStateOf(DeviceStorage.availableBytes(context)) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    // Addresses shown as chips, and the text still being typed. Together
    // they are the saved recipients setting; see saveRecipients().
    var committedRecipients by remember { mutableStateOf(EmailSettings.parseRecipients(settings.emailRecipients)) }
    var recipientDraft by remember { mutableStateOf("") }
    var recipientsFocused by remember { mutableStateOf(false) }

    val recipients = EmailSettings.parseRecipients(settings.emailRecipients)
    val hasInvalidRecipient = recipients.any { !EmailSettings.isValidEmail(it) }

    // Addresses to flag in the footer. What's still being typed is left out
    // until the field loses focus, so a half-typed address isn't an error.
    val typing = if (recipientsFocused) EmailSettings.parseRecipients(recipientDraft) else emptyList()
    val invalidRecipients = recipients.filter { !EmailSettings.isValidEmail(it) && it !in typing }

    // Writes the chips plus whatever is in the field to the stored setting.
    // The half-typed text is included so an address still gets the email if
    // the user never presses Done.
    fun saveRecipients() {
        settings.emailRecipients =
            (committedRecipients + EmailSettings.parseRecipients(recipientDraft)).joinToString(", ")
    }

    // Turns each valid address in the field into a chip; anything else stays
    // in the field to be fixed.
    fun commitDraft() {
        val rejected = mutableListOf<String>()
        for (address in EmailSettings.parseRecipients(recipientDraft)) {
            if (!EmailSettings.isValidEmail(address)) {
                rejected += address
            } else if (committedRecipients.none { it.equals(address, ignoreCase = true) }) {
                committedRecipients = committedRecipients + address
            }
        }
        recipientDraft = rejected.joinToString(", ")
        saveRecipients()
    }

    val storageRequester = remember { BringIntoViewRequester() }
    val locationRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(scrollTarget) {
        val requester = when (scrollTarget) {
            SettingsSection.STORAGE -> storageRequester
            SettingsSection.LOCATION -> locationRequester
            null -> return@LaunchedEffect
        }
        // The first time the tab is opened the screen hasn't been laid out
        // yet, and scrolling before that does nothing.
        delay(SCROLL_TO_TARGET_DELAY_MS)
        requester.bringIntoView()
        onScrolledToTarget()
    }
    val isDeviceStorageLow = deviceFreeBytes?.let {
        DeviceStorage.isLow(it, graph.driveStorageManager.bytesPerSecondEstimate())
    } ?: false

    LaunchedEffect(Unit) {
        if (EmailSettings.isDefaultSubject(settings.emailSubject)) {
            settings.emailSubject = EmailSettings.DEFAULT_SUBJECT
        }
        graph.driveStorageManager.refreshIfStale()
    }

    fun sendTestEmail() {
        focusManager.clearFocus()
        val email = EmailSettings.from(settings, graph.authManager.userName.value, graph.authManager.userEmail.value)
        testEmailState = TestEmailState.Sending
        // App scope so leaving the tab doesn't cancel a send in flight.
        graph.appScope.launch {
            testEmailState = try {
                val token = graph.authManager.validAccessToken()
                GmailService.sendEmergencyEmail(
                    to = email.recipients,
                    subject = "[Test] " + email.subject,
                    bodyNote = "This is a test from Just Capture. A real notification also includes a link to your " +
                        "recording in Google Drive." + if (email.note.isEmpty()) "" else "\n\n${email.note}",
                    startedAtMs = System.currentTimeMillis(),
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Settings", modifier = Modifier.weight(1f))
            IconButton(onClick = { showHelp = true }, modifier = Modifier.padding(end = 4.dp, top = 8.dp)) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "How it works", tint = AppColors.accent)
            }
        }
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ---- Account ----
            Section(
                header = "Account",
                footer = if (publicSharingBlocked) {
                    {
                        FooterWarning(
                            "This account doesn't allow public links, so the people you notify won't be able to open " +
                                "your videos. This is usually a work or school account policy. Sign in with a " +
                                "personal Google account instead."
                        )
                    }
                } else {
                    null
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Avatar((userEmail ?: "?").take(1).uppercase())
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Google Account", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
            Section(header = "Recording") {
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
                Box(Modifier.bringIntoViewRequester(locationRequester)) {
                    SwitchRow("Save location", settings.includeLocationInEmail) {
                        settings.includeLocationInEmail = it
                    }
                }
            }

            // ---- Email notifications ----
            Section(
                header = "Email Notifications",
                footer = {
                    when {
                        !settings.emailAlertsEnabled -> {}
                        invalidRecipients.isNotEmpty() -> FooterText(
                            "Check ${invalidRecipients.joinToString(", ")} — " +
                                "${if (invalidRecipients.size == 1) "it doesn't" else "they don't"} look like an email address.",
                            AppColors.record
                        )

                        recipients.isEmpty() -> FooterText(
                            "Add people to email when you start recording, or turn off email notifications to dismiss this warning.",
                            AppColors.warning
                        )
                        else -> {}
                    }
                }
            ) {
                SwitchRow("Email notifications", settings.emailAlertsEnabled) { settings.emailAlertsEnabled = it }
                if (settings.emailAlertsEnabled) {
                    RecipientField(
                        recipients = committedRecipients,
                        draft = recipientDraft,
                        onDraftChange = { draft ->
                            recipientDraft = draft
                            // A comma, space or pasted list finishes an address.
                            if (draft.any { it in RECIPIENT_SEPARATORS }) commitDraft() else saveRecipients()
                        },
                        onCommit = ::commitDraft,
                        onRemove = { address ->
                            committedRecipients = committedRecipients - address
                            saveRecipients()
                        },
                        onFocusChanged = { focused ->
                            if (recipientsFocused && !focused) commitDraft()
                            recipientsFocused = focused
                        }
                    )
                    val canTest =
                        recipients.isNotEmpty() && !hasInvalidRecipient &&
                            testEmailState != TestEmailState.Sending
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (canTest) 1f else 0.4f)
                            .clickable(enabled = canTest, onClick = ::sendTestEmail)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = AppColors.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Send Test Email",
                            color = AppColors.accent,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                        when (val state = testEmailState) {
                            TestEmailState.Idle -> {}

                            TestEmailState.Sending -> CircularProgressIndicator(
                                color = AppColors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )

                            TestEmailState.Sent -> StatusLabel("Sent", Icons.Filled.CheckCircle, AppColors.success)

                            is TestEmailState.Failed -> StatusLabel(
                                state.message,
                                Icons.Filled.Warning,
                                AppColors.record
                            )
                        }
                    }
                }
            }

            // ---- Email content ----
            if (settings.emailAlertsEnabled) {
                Section(header = "Email Content") {
                    OutlinedTextField(
                        value = settings.emailSubject,
                        // Multi-line so a long subject wraps at large text sizes,
                        // but a subject can't hold a newline, so Enter ends editing.
                        onValueChange = {
                            if (it.contains('\n')) {
                                settings.emailSubject = it.replace("\n", "")
                                focusManager.clearFocus()
                            } else {
                                settings.emailSubject = it
                            }
                        },
                        label = { Text("Subject") },
                        placeholder = { Text(EmailSettings.DEFAULT_SUBJECT) },
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
                }
            }

            // ---- Storage ----
            Section(
                header = "Storage",
                footer = {
                    graph.driveStorageManager.estimatedRecordingSeconds()?.let {
                        FooterText("Enough space for about ${Formatters.recordingTime(it)} of recording.")
                    }
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.bringIntoViewRequester(storageRequester)
                ) {
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
                    if (isRefreshingStorage) {
                        CircularProgressIndicator(
                            color = AppColors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    }
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
                footer = { FooterText("Just Capture records in short clips and tries to upload each one to your Google Drive as it goes.\n\nJust Capture doesn't contact emergency services. If you're in danger, call your local emergency number.") }
            ) {
                Row {
                    Text("Version", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(appVersion(context), color = AppColors.secondaryText, fontSize = 16.sp)
                }
                Divider()
                LinkRow("Website", AppConfig.HOMEPAGE_URL)
                Divider()
                LinkRow("Privacy Policy", AppConfig.PRIVACY_POLICY_URL)
                Divider()
                LinkRow("Terms of Service", AppConfig.TERMS_URL)
            }
        }
    }

    if (showHelp) {
        ModalBottomSheet(onDismissRequest = { showHelp = false }, containerColor = AppColors.background) {
            SettingsHelp()
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text("Sign out of Google?") },
            text = { Text("Recording stops, and uploads and notification emails pause until you sign back in.") },
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

/** Longer explanations of the settings, kept out of the screen's footers. */
@Composable
private fun SettingsHelp() {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("How It Works", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        HelpSection("Recording") {
            HelpRow(Icons.Filled.Videocam, "Saved as you go",
                "Your video is saved in short clips. Each one uploads to your Google Drive as soon as it's done, " +
                    "so what's uploaded stays safe even if you lose your phone.")
            HelpRow(Icons.Filled.LocationOn, "Your location",
                "With Save location on, the app notes where you are when you start and after each clip. " +
                    "It's kept on your phone and in your Drive.")
        }
        HelpSection("Notifying People") {
            HelpRow(Icons.Filled.Email, "An email when you start",
                "The people you add get an email from your Gmail with a link to your recording.")
            HelpRow(Icons.AutoMirrored.Filled.Chat, "What it says",
                "When you started, your message, and your location if it's on. Type [your_name] and it shows your name.")
            HelpRow(Icons.Filled.CloudOff, "If your Drive fills up",
                "They get an email when it's almost full, or once it's full. Just Capture keeps trying, so if space is freed or someone adds " +
                    "storage to your Google account, like whoever manages your family plan, the rest of your clips " +
                    "upload on their own. This works as long as your phone is on and the app hasn't been force-closed.")
        }
        HelpSection("Storage") {
            HelpRow(Icons.Filled.PhoneAndroid, "Clips stay on your phone",
                "To free up space, delete old recordings in My Clips.")
            HelpRow(Icons.Filled.CloudDone, "Your Drive is never touched",
                "Just Capture never deletes anything from your Google Drive.")
        }
    }
}

@Composable
private fun HelpSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = AppColors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp))
        Column(modifier = Modifier.card(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun HelpRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = AppColors.accent, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = AppColors.secondaryText, fontSize = 14.sp)
        }
    }
}

private fun appVersion(context: android.content.Context): String {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()

    @Suppress("DEPRECATION")
    val code =
        info?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 1
    return "${info?.versionName ?: "1.0"} ($code)"
}

private const val SCROLL_TO_TARGET_DELAY_MS = 150L
private const val RECIPIENT_SEPARATORS = ",; \n\t"

/**
 * One accepted notification address: a round initial, the address, and a
 * button to remove it.
 */
/**
 * One box holding the added addresses as chips with the typing spot after
 * them, like the To line of an email. Backspace in an empty spot removes the
 * last chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientField(
    recipients: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
    onRemove: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) AppColors.accent else MaterialTheme.colorScheme.outline,
                shape
            )
            .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "To",
            color = AppColors.secondaryText,
            fontSize = 16.sp,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        recipients.forEach { address ->
            RecipientChip(address, EmailSettings.isValidEmail(address)) { onRemove(address) }
        }
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(AppColors.accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            // Stays in the field to add another.
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) {
                        Text(
                            if (recipients.isEmpty()) "Add email address" else "Add another",
                            color = AppColors.tertiaryText,
                            fontSize = 16.sp,
                            maxLines = 1
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                // Fills the rest of the line, or wraps to its own when too narrow.
                .weight(1f)
                .align(Alignment.CenterVertically)
                .widthIn(min = 120.dp)
                .padding(vertical = 6.dp)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChanged(it.isFocused)
                }
                .onPreviewKeyEvent { event ->
                    val removeLast = event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                        draft.isEmpty() && recipients.isNotEmpty()
                    if (removeLast) onRemove(recipients.last())
                    removeLast
                }
                .semantics { contentDescription = "Add email address" }
        )
    }
}

@Composable
private fun RecipientChip(address: String, isValid: Boolean, onRemove: () -> Unit) {
    val color = if (isValid) AppColors.accent else AppColors.record
    Row(
        modifier = Modifier
            .background(AppColors.surfaceRaised, CircleShape)
            .border(1.dp, if (isValid) AppColors.stroke else AppColors.record.copy(alpha = 0.7f), CircleShape)
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (isValid) address.take(1).uppercase() else "!",
                color = if (isValid) AppColors.onAccent else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        // A long address is cut at the end, so who it is stays readable.
        Text(
            address,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove $address",
                tint = AppColors.secondaryText,
                modifier = Modifier.size(14.dp)
            )
        }
    }
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

@Composable
private fun LinkRow(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        label,
        color = AppColors.accent,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(vertical = 4.dp)
    )
}

/** The signed-in user's initial on the brand gradient. */
@Composable
private fun Avatar(initial: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(AppColors.brandGradient),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}
