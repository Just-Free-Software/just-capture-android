package fyi.justfreesoftware.justcapture.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared look for the whole app, taken from the launcher icon: a warm
 * near-black background, solid warm-brown surfaces and the icon's orange as
 * the accent. Surfaces are opaque and separated by tone, not translucency.
 */
object AppColors {
    val background = Color(0xFF110C09)
    val surface = Color(0xFF1C1511)
    val surfaceRaised = Color(0xFF2A201A)
    val stroke = Color(0xFF3A2E26)
    val secondaryText = Color(0xFFBBAFA5)
    val tertiaryText = Color(0xFF85786E)

    val accent = Color(0xFFFF8A3A)
    val onAccent = Color(0xFF2B1206)
    val accentContainer = Color(0xFF3D2416)
    val record = Color(0xFFFF453A)
    val success = Color(0xFF4CD08A)
    val warning = Color(0xFFF5C542)

    /** Backing for controls drawn over the camera preview. */
    val overlay = Color(0xE0110C09)

    val brandGradient = Brush.linearGradient(listOf(Color(0xFFFFA552), Color(0xFFFF6A1E)))
}

val CardShape = RoundedCornerShape(16.dp)

/** Fixed-width digits so timers and counters don't jitter as they change. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

private val DarkColors = darkColorScheme(
    primary = AppColors.accent,
    onPrimary = AppColors.onAccent,
    primaryContainer = AppColors.accentContainer,
    onPrimaryContainer = Color(0xFFFFDBC7),
    secondary = AppColors.accent,
    onSecondary = AppColors.onAccent,
    secondaryContainer = AppColors.accentContainer,
    onSecondaryContainer = Color(0xFFFFDBC7),
    background = AppColors.background,
    onBackground = Color.White,
    surface = AppColors.background,
    onSurface = Color.White,
    surfaceVariant = AppColors.surfaceRaised,
    onSurfaceVariant = AppColors.secondaryText,
    surfaceTint = AppColors.accent,
    surfaceContainerLowest = Color(0xFF0B0806),
    surfaceContainerLow = Color(0xFF17110E),
    surfaceContainer = AppColors.surface,
    surfaceContainerHigh = Color(0xFF241B16),
    surfaceContainerHighest = AppColors.surfaceRaised,
    outline = Color(0xFF6B5A4E),
    outlineVariant = AppColors.stroke,
    error = AppColors.record,
    onError = Color.White
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
    .padding(padding)

/** Dark pill used for overlays on top of the camera preview. */
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(CardShape)
            .background(AppColors.surfaceRaised)
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(style.color)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = style.color, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (message != null) {
                    Text(message, color = AppColors.secondaryText, fontSize = 12.sp)
                }
                if (actionTitle != null && onAction != null) {
                    TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                        Text(actionTitle, color = style.color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
}

/** Big number with a caption, used for session stats. */
@Composable
fun StatView(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold, style = TabularNumbers)
        Text(title, color = AppColors.secondaryText, fontSize = 12.sp)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = AppColors.accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 4.dp)
    )
}

@Composable
fun HSpacer(width: Dp) = Spacer(Modifier.width(width))
