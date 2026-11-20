package fyi.justfreesoftware.justcapture.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import fyi.justfreesoftware.justcapture.AppGraph
import fyi.justfreesoftware.justcapture.model.SessionLocationInfo
import fyi.justfreesoftware.justcapture.model.VideoChunk
import fyi.justfreesoftware.justcapture.services.DriveSessionDownloader
import fyi.justfreesoftware.justcapture.services.DriveSessionDownloader.SyncState
import fyi.justfreesoftware.justcapture.ui.components.LocalOverlayHost
import fyi.justfreesoftware.justcapture.ui.theme.AppColors
import fyi.justfreesoftware.justcapture.ui.theme.TabularNumbers
import fyi.justfreesoftware.justcapture.ui.theme.card
import fyi.justfreesoftware.justcapture.util.Formatters
import java.io.File
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * A sheet over the whole app with a title bar and a Done button. In
 * [isFullscreen] the title bar and system bars go, the screen may turn
 * sideways, and Back leaves full screen (via [onExitFullscreen]) instead of
 * closing the sheet.
 */
@Composable
private fun PlayerSheet(
    title: String,
    onDismiss: () -> Unit,
    isFullscreen: Boolean = false,
    onExitFullscreen: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    // Read inside the overlay, so it follows changes made here.
    val currentTitle by rememberUpdatedState(title)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentFullscreen by rememberUpdatedState(isFullscreen)
    val currentOnExitFullscreen by rememberUpdatedState(onExitFullscreen)
    val currentContent by rememberUpdatedState(content)

    val host = LocalOverlayHost.current
    DisposableEffect(host) {
        host.value = {
            PlayerSheetBody(currentTitle, currentOnDismiss, currentFullscreen, currentOnExitFullscreen, currentContent)
        }
        onDispose { host.value = null }
    }
}

@Composable
private fun PlayerSheetBody(
    title: String,
    onDismiss: () -> Unit,
    isFullscreen: Boolean,
    onExitFullscreen: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    BackHandler { if (isFullscreen) onExitFullscreen() else onDismiss() }
    FullscreenEffect(isFullscreen)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isFullscreen) Color.Black else AppColors.background)
            // Taps mustn't reach the screen underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            // Full screen keeps clear of the camera cutout only; otherwise
            // of the status and navigation bars too.
            .windowInsetsPadding(if (isFullscreen) WindowInsets.displayCutout else WindowInsets.safeDrawing)
    ) {
        if (!isFullscreen) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text("Done", color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * While full screen, hides the status and navigation bars (a swipe brings
 * them back briefly) and lets the screen follow the phone's rotation, which
 * the app otherwise locks to portrait. Both are undone on leaving.
 */
@Composable
private fun FullscreenEffect(isFullscreen: Boolean) {
    val view = LocalView.current
    DisposableEffect(isFullscreen) {
        val activity = view.context.findActivity()
        val insets = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        if (isFullscreen) {
            insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets?.hide(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        onDispose {
            if (isFullscreen) {
                insets?.show(WindowInsetsCompat.Type.systemBars())
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    /** When set, the player's controls include a full screen button. */
    onFullscreenChange: ((Boolean) -> Unit)? = null
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                if (onFullscreenChange != null) {
                    setFullscreenButtonClickListener { onFullscreenChange(it) }
                }
            }
        },
        // Keeps the button's icon right when Back, not the button, left full screen.
        update = { if (onFullscreenChange != null) it.setFullscreenButtonState(isFullscreen) },
        modifier = modifier
    )
}

/** Plays one clip saved on this phone. */
@Composable
fun LocalClipPlayer(chunk: VideoChunk, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val file = File(chunk.localPath)
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            if (file.exists()) {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                playWhenReady = true
            }
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    PlayerSheet(
        title = "Clip ${chunk.index + 1} · ${Formatters.duration(
            chunk.durationSec
        )} · ${Formatters.bytes(chunk.fileSizeBytes)}",
        onDismiss = onDismiss
    ) {
        if (file.exists()) {
            VideoSurface(player, Modifier.fillMaxSize())
        } else {
            EmptyState(
                icon = Icons.Filled.Cloud,
                title = "Clip Not on This Phone",
                message = "It isn't saved on this phone. Open the session in Google Drive to watch it."
            )
        }
    }
}

/**
 * Plays a Drive session folder's clips back to back, in playlist order.
 * Clips already on the phone play straight away; new ones on Drive are
 * fetched on request and appended without restarting playback.
 */
@Composable
fun DrivePlaybackScreen(
    graph: AppGraph,
    folderId: String,
    source: DriveSessionDownloader.Source,
    title: String,
    /** The session's clips recorded on this phone, which play from where they are instead of being downloaded back. */
    localClips: List<DriveSessionDownloader.LocalClip> = emptyList(),
    locationText: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember(folderId, source) {
        DriveSessionDownloader(context, folderId, source, localClips, locationText)
    }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var isFullscreen by remember { mutableStateOf(false) }

    LaunchedEffect(downloader) {
        downloader.loadSaved()
        if (downloader.playableFiles.isEmpty()) {
            downloader.downloadNewClips(graph.authManager)
        } else if (!downloader.hasAllClipsLocally) {
            // One listing request, so the screen can say whether there's
            // anything new. Skipped when this phone recorded the session and
            // still has every clip.
            downloader.checkForNewClips(graph.authManager)
        }
    }

    // Appends newly downloaded clips to the playlist, keeping the viewer's
    // place. If they had watched to the end, it carries on into the new clips.
    LaunchedEffect(downloader) {
        snapshotFlow { downloader.playableFiles }.collect { files ->
            val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val added = files.filter { it.path !in existing }
            if (added.isEmpty()) return@collect
            val isFirst = player.mediaItemCount == 0
            val reachedEnd = player.playbackState == Player.STATE_ENDED
            player.addMediaItems(added.map { MediaItem.Builder().setUri(Uri.fromFile(it)).setMediaId(it.path).build() })
            if (isFirst) {
                player.prepare()
                player.playWhenReady = true
            } else if (reachedEnd) {
                player.seekToNextMediaItem()
                player.play()
            }
        }
    }

    // Saves what was learned about a shared folder whenever a sync settles.
    LaunchedEffect(downloader) {
        snapshotFlow { downloader.sync }.filter { !it.isBusy }.collect {
            graph.sharedFolderStore.update(downloader)
        }
    }

    PlayerSheet(
        title = downloader.folderName ?: title,
        onDismiss = onDismiss,
        // Only the player can be full screen, not the loading or error states.
        isFullscreen = isFullscreen && downloader.playableFiles.isNotEmpty(),
        onExitFullscreen = { isFullscreen = false }
    ) {
        val sync = downloader.sync
        when {
            downloader.playableFiles.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val locationInfo = remember(downloader.locationText) {
                    downloader.locationText?.let { SessionLocationInfo.parse(it) }
                }
                VideoSurface(
                    player,
                    Modifier.weight(1f).fillMaxWidth(),
                    isFullscreen = isFullscreen,
                    onFullscreenChange = { isFullscreen = it }
                )
                val statusCard = @Composable {
                    StatusCard(
                        downloader = downloader,
                        onDownload = { scope.launch { downloader.downloadNewClips(graph.authManager) } },
                        onCheck = { scope.launch { downloader.checkForNewClips(graph.authManager) } }
                    )
                }
                if (isFullscreen) {
                    // The video has the whole screen.
                } else if (locationInfo != null) {
                    // With location details the player takes the top of the
                    // screen and the rest scrolls.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        statusCard()
                        SessionLocationCard(locationInfo)
                    }
                } else {
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) { statusCard() }
                }
            }

            sync is SyncState.Failed -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = AppColors.tertiaryText,
                    modifier = Modifier.size(52.dp)
                )
                Text("Couldn't Load From Drive", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(sync.message, color = AppColors.secondaryText, fontSize = 14.sp, textAlign = TextAlign.Center)
                Button(onClick = { scope.launch { downloader.downloadNewClips(graph.authManager) } }) {
                    Text("Try Again", fontWeight = FontWeight.SemiBold)
                }
            }

            else -> FirstDownloadProgress(downloader, Modifier.align(Alignment.Center))
        }
    }
}

private fun byteFraction(downloader: DriveSessionDownloader, completed: Int, total: Int): Float =
    if (downloader.totalBytes > 0) {
        (downloader.downloadedBytes.toFloat() / downloader.totalBytes).coerceIn(0f, 1f)
    } else {
        completed.toFloat() / maxOf(total, 1)
    }

@Composable
private fun StatusCard(
    downloader: DriveSessionDownloader,
    onDownload: () -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.card(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = AppColors.success,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text("Saved on this phone", color = AppColors.success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val count = downloader.segments.size
            Text(
                "$count clip${if (count == 1) "" else "s"}",
                color = AppColors.secondaryText,
                fontSize = 12.sp,
                style = TabularNumbers
            )
        }

        // Only after a check, so it isn't claimed before Drive was asked.
        if (downloader.locationText == null && downloader.lastCheckedAtMs != null) {
            Text("No location was saved with this recording.", color = AppColors.secondaryText, fontSize = 12.sp)
        }

        if (downloader.skippedClips > 0) {
            val n = downloader.skippedClips
            Text(
                "$n clip${if (n == 1) "" else "s"} couldn't be read and ${if (n == 1) "was" else "were"} skipped.",
                color = AppColors.warning,
                fontSize = 12.sp
            )
        }

        when (val sync = downloader.sync) {
            SyncState.Checking -> ProgressRow("Checking Drive for new clips…")

            SyncState.Preparing -> ProgressRow("Adding new clips…")

            is SyncState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { byteFraction(downloader, sync.completed, sync.total) },
                    color = AppColors.accent,
                    trackColor = AppColors.surfaceRaised,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Downloading new clip ${sync.completed + 1} of ${sync.total}",
                    color = AppColors.secondaryText,
                    fontSize = 12.sp
                )
            }

            SyncState.Idle, is SyncState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val checked = downloader.lastCheckedAtMs
                if (sync is SyncState.Failed) {
                    Text(sync.message, color = AppColors.warning, fontSize = 12.sp)
                } else if (checked != null && downloader.newClipIds.isEmpty()) {
                    Text(
                        "Up to date · checked ${Formatters.time(checked)}",
                        color = AppColors.secondaryText,
                        fontSize = 12.sp
                    )
                }
                val newCount = downloader.newClipIds.size
                if (newCount > 0) {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Get $newCount New Clip${if (newCount == 1) "" else "s"}",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                        Text("Check for New Clips", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(color = AppColors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text(text, color = AppColors.secondaryText, fontSize = 14.sp)
    }
}

@Composable
private fun FirstDownloadProgress(downloader: DriveSessionDownloader, modifier: Modifier) {
    Column(
        modifier = modifier
            .padding(32.dp)
            .card(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when (val sync = downloader.sync) {
            is SyncState.Downloading -> {
                LinearProgressIndicator(
                    progress = { byteFraction(downloader, sync.completed, sync.total) },
                    color = AppColors.accent,
                    trackColor = AppColors.surfaceRaised,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Downloading clip ${sync.completed + 1} of ${sync.total}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (downloader.totalBytes > 0) {
                    Text(
                        "${Formatters.bytes(downloader.downloadedBytes)} of ${Formatters.bytes(downloader.totalBytes)}",
                        color = AppColors.secondaryText,
                        fontSize = 12.sp
                    )
                }
            }

            else -> {
                CircularProgressIndicator(color = AppColors.accent)
                Text(
                    if (sync == SyncState.Preparing) "Preparing playback…" else "Finding clips…",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
