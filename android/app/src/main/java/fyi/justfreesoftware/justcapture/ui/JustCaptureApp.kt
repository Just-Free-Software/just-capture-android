package fyi.justfreesoftware.justcapture.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.justfreesoftware.justcapture.AppGraph
import fyi.justfreesoftware.justcapture.R
import fyi.justfreesoftware.justcapture.config.AppConfig
import fyi.justfreesoftware.justcapture.ui.screens.MyClipsScreen
import fyi.justfreesoftware.justcapture.ui.screens.RecordingScreen
import fyi.justfreesoftware.justcapture.ui.screens.SettingsScreen
import fyi.justfreesoftware.justcapture.ui.screens.WatchScreen
import fyi.justfreesoftware.justcapture.ui.theme.AppColors

@Composable
fun JustCaptureApp(graph: AppGraph) {
    val isSignedIn by graph.authManager.isSignedIn.collectAsState()
    if (isSignedIn) {
        MainTabs(graph)
    } else {
        SignInScreen(graph)
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Record", Icons.Filled.Videocam),
    Tab("My Clips", Icons.Filled.VideoLibrary),
    Tab("Watch", Icons.Filled.SlowMotionVideo),
    Tab("Settings", Icons.Filled.Settings)
)

@Composable
private fun MainTabs(graph: AppGraph) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = AppColors.background,
        // Each screen pads for the status bar itself (the camera preview runs
        // underneath it), so the scaffold only reserves the bottom bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = AppColors.surface, tonalElevation = 0.dp) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppColors.accent,
                            selectedTextColor = AppColors.accent,
                            indicatorColor = AppColors.accentContainer,
                            unselectedIconColor = AppColors.secondaryText,
                            unselectedTextColor = AppColors.secondaryText
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> RecordingScreen(graph)
                1 -> MyClipsScreen(graph)
                2 -> WatchScreen(graph)
                3 -> SettingsScreen(graph)
            }
        }
    }
}

@Composable
private fun SignInScreen(graph: AppGraph) {
    val isAuthenticating by graph.authManager.isAuthenticating.collectAsState()
    val signInError by graph.authManager.signInError.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background)
    ) {
        // Warm glow behind the logo, like the one behind the icon's camcorder.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            0f to AppColors.accent.copy(alpha = 0.18f),
                            1f to Color.Transparent,
                            center = Offset(size.width / 2, size.height * 0.36f),
                            radius = size.width * 0.85f
                        )
                    )
                }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(28.dp))
            )
            Spacer(Modifier.height(24.dp))

            Text("Just Capture", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text(
                "Record. Back up. Notify.",
                color = AppColors.secondaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )

            Column(
                modifier = Modifier.padding(top = 40.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Feature(Icons.Filled.CloudUpload, "As you record, each clip is uploaded to your Google Drive when you have a connection.")
                Feature(Icons.Filled.MarkEmailUnread, "The people you choose get an email with a link to watch.")
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { graph.authManager.signIn(context) },
                enabled = !isAuthenticating,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = AppColors.accent.copy(alpha = 0.6f),
                    disabledContentColor = AppColors.onAccent
                ),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                if (isAuthenticating) {
                    CircularProgressIndicator(color = AppColors.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (isAuthenticating) "Signing in…" else "Continue with Google",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                "Just Capture asks for permission to save to Drive and send email from your Gmail account.",
                color = AppColors.tertiaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp, start = 16.dp, end = 16.dp)
            )
            TextButton(
                onClick = { runCatching { uriHandler.openUri(AppConfig.PRIVACY_POLICY_URL) } },
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Text("Privacy Policy", color = AppColors.secondaryText, fontSize = 12.sp)
            }
        }
    }

    signInError?.let { message ->
        AlertDialog(
            onDismissRequest = { graph.authManager.signInError.value = null },
            title = { Text("Couldn't Sign In") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { graph.authManager.signInError.value = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(AppColors.accentContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(22.dp))
        }
        Text(text, color = Color.White.copy(alpha = 0.88f), fontSize = 15.sp)
    }
}
