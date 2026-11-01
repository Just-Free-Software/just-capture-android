package fyi.justfreesoftware.justcapture.ui.screens

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fyi.justfreesoftware.justcapture.model.VideoChunk
import fyi.justfreesoftware.justcapture.util.Formatters
import java.io.File

/**
 * In-app playback for a locally saved chunk, mirroring the iOS app's
 * VideoPlayerView (sheet with title, player, and Done).
 */
@Composable
fun VideoPlayerDialog(chunk: VideoChunk, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fileExists = remember(chunk.id) { File(chunk.localPath).exists() }
    val videoView = remember { androidx.compose.runtime.mutableStateOf<VideoView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    ) {
        Surface(
            color = Color.Black,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Chunk ${chunk.index + 1} (${Formatters.formatBytes(context, chunk.fileSizeBytes)})",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(12.dp))

                if (!fileExists) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Block,
                            contentDescription = null,
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "Local file no longer available",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "This chunk was deleted after a successful upload.",
                            color = Color(0xFF888888),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(chunk.localPath)
                                val controller = MediaController(ctx)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = false
                                    start()
                                }
                                videoView.value = this
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        videoView.value?.stopPlayback()
                        onDismiss()
                    }
                ) {
                    Text("Done")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { videoView.value?.stopPlayback() }
    }
}
