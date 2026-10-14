package com.justapp.record.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justapp.record.model.UploadStatus
import com.justapp.record.model.VideoChunk
import com.justapp.record.util.Formatters
import java.io.File
import java.util.Locale

/** Per-chunk upload status card, mirroring the iOS app's StatusCardView. */
@Composable
fun StatusCard(
    chunk: VideoChunk,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val statusColor = chunk.status.color
    val localFileExists = rememberFileExists(chunk.localPath)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE6161616), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Left
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(statusColor.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${chunk.index + 1}",
                color = statusColor,
                fontWeight = FontWeight.Bold
            )
        }

        // Middle
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Chunk ${chunk.index + 1}",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${Formatters.formatBytes(context, chunk.fileSizeBytes)} • " +
                    String.format(Locale.US, "%.1fs", chunk.durationSec),
                color = Color(0xFFAAAAAA),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
            if (chunk.status == UploadStatus.UPLOADING) {
                Spacer(Modifier.size(0.dp))
                LinearProgressIndicator(
                    progress = { chunk.uploadProgress.toFloat().coerceIn(0f, 1f) },
                    color = statusColor,
                    trackColor = Color(0xFF333333),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Right
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.1f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = chunk.status.icon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = chunk.status.label,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (chunk.status == UploadStatus.FAILED) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Retry upload",
                            tint = Color(0xFFFFA726),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onPlay,
                    enabled = localFileExists,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayCircleFilled,
                        contentDescription = "Play chunk",
                        tint = if (localFileExists) Color(0xFF42A5F5) else Color(0xFF666666),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberFileExists(path: String): Boolean {
    return androidx.compose.runtime.remember(path) { File(path).exists() }
}
