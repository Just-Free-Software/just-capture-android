package fyi.justfreesoftware.justcapture.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Pulsing red dot, mirroring the iOS app's PulseIndicator. */
@Composable
fun PulseIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )

    Box(modifier = modifier.size(12.dp)) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - (scale - 1f)
                }
                .background(fyi.justfreesoftware.justcapture.ui.theme.AppColors.record, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(fyi.justfreesoftware.justcapture.ui.theme.AppColors.record, CircleShape)
        )
    }
}
