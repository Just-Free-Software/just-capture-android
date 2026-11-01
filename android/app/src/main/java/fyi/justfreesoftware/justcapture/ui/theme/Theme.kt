package fyi.justfreesoftware.justcapture.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared look for the whole app: one dark palette, one card style, one set
 * of status colours, matching the iOS app's Theme.
 */
object AppColors {
    val background = Color(0xFF0A0A0F)
    val surface = Color.White.copy(alpha = 0.06f)
    val surfaceRaised = Color.White.copy(alpha = 0.10f)
    val stroke = Color.White.copy(alpha = 0.08f)
    val secondaryText = Color.White.copy(alpha = 0.6f)
    val tertiaryText = Color.White.copy(alpha = 0.4f)

    val accent = Color(0xFF5C8CFF)
    val record = Color(0xFFFF4545)
    val success = Color(0xFF40D687)
    val warning = Color(0xFFFFAD33)

    val overlay = Color.Black.copy(alpha = 0.45f)

    val brandGradient = Brush.linearGradient(listOf(Color(0xFFFF5959), Color(0xFF9E54FF)))
}

val CardShape = RoundedCornerShape(18.dp)

private val DarkColors = darkColorScheme(
    primary = AppColors.accent,
    onPrimary = Color.White,
    secondary = AppColors.accent,
    background = AppColors.background,
    onBackground = Color.White,
    surface = Color(0xFF16161C),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E26),
    onSurfaceVariant = AppColors.secondaryText,
    surfaceContainer = Color(0xFF121218),
    error = AppColors.record
)

@Composable
fun JustCaptureTheme(content: @Composable () -> Unit) {
    // The iOS app forces dark mode; do the same regardless of system setting.
    MaterialTheme(colorScheme = DarkColors, content = content)
}

/** Standard content card used on My Clips, Watch and detail screens. */
fun Modifier.card(padding: Dp = 16.dp): Modifier = this
    .fillMaxWidth()
    .background(AppColors.surface, CardShape)
    .border(1.dp, AppColors.stroke, CardShape)
    .padding(padding)

/** Translucent pill used for overlays on top of the camera preview. */
fun Modifier.overlayPill(): Modifier = this
    .background(AppColors.overlay, RoundedCornerShape(50))
    .padding(horizontal = 12.dp, vertical = 8.dp)

/** Small coloured capsule with an icon and label, e.g. "Uploaded". */
@Composable
fun StatusBadge(text: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

enum class NoticeStyle(val color: Color) {
    ERROR(AppColors.record),
    WARNING(AppColors.warning),
    INFO(AppColors.accent)
}

/** Full-width banner for problems the user should know about right now. */
@Composable
fun NoticeBanner(
    style: NoticeStyle,
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE6141418), shape)
            .background(style.color.copy(alpha = 0.18f), shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = style.color, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (message != null) {
                Text(message, color = AppColors.secondaryText, fontSize = 12.sp)
            }
            if (actionTitle != null && onAction != null) {
                TextButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(actionTitle, color = style.color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = AppColors.secondaryText, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Big number with a caption, used for session stats. */
@Composable
fun StatView(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(title, color = AppColors.secondaryText, fontSize = 12.sp)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = AppColors.secondaryText,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 4.dp)
    )
}

@Composable
fun HSpacer(width: Dp) = Spacer(Modifier.width(width))
