package fyi.justfreesoftware.justcapture.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.justfreesoftware.justcapture.AppGraph
import fyi.justfreesoftware.justcapture.model.EmailJob
import fyi.justfreesoftware.justcapture.model.EmailStatus
import fyi.justfreesoftware.justcapture.model.RecordingSession
import fyi.justfreesoftware.justcapture.model.UploadStatus
import fyi.justfreesoftware.justcapture.model.VideoChunk
import fyi.justfreesoftware.justcapture.services.DriveSessionDownloader
import fyi.justfreesoftware.justcapture.ui.components.color
import fyi.justfreesoftware.justcapture.ui.components.icon
import fyi.justfreesoftware.justcapture.ui.components.label
import fyi.justfreesoftware.justcapture.ui.components.statusSummary
import fyi.justfreesoftware.justcapture.ui.theme.AppColors
import fyi.justfreesoftware.justcapture.ui.theme.TabularNumbers
import fyi.justfreesoftware.justcapture.ui.theme.CardShape
import fyi.justfreesoftware.justcapture.ui.theme.SectionHeader
import fyi.justfreesoftware.justcapture.ui.theme.StatView
import fyi.justfreesoftware.justcapture.ui.theme.StatusBadge
import fyi.justfreesoftware.justcapture.ui.theme.card
import fyi.justfreesoftware.justcapture.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MyClipsScreen(graph: AppGraph) {
    val sessions by graph.sessionStore.sessions.collectAsState()
    var openSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionToDelete by remember { mutableStateOf<RecordingSession?>(null) }

    val openSession = openSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
    if (openSessionId != null) {
        BackHandler { openSessionId = null }
        if (openSession != null) {
            SessionDetailScreen(graph, openSession, onBack = { openSessionId = null })
        } else {
            // Deleted while open.
            openSessionId = null
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .statusBarsPadding()
    ) {
        ScreenTitle("My Clips")
        if (sessions.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Videocam,
                title = "No Recordings Yet",
                message = "Sessions you record show up here, with their upload and alert status."
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Summary(sessions) }
                item { SectionHeader("Sessions") }
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onClick = { openSessionId = session.id },
                        onLongClick = { if (!session.isActive) sessionToDelete = session }
                    )
                }
            }
        }
    }

    sessionToDelete?.let { session ->
        DeleteSessionDialog(graph, session, onDone = { sessionToDelete = null })
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.tertiaryText, modifier = Modifier.size(56.dp))
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(message, color = AppColors.secondaryText, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Summary(sessions: List<RecordingSession>) {
    val totalDuration = sessions.sumOf { it.totalDurationSec }
    val totalQueued = sessions.sumOf { it.queuedCount }
    val totalFailed = sessions.sumOf { it.failedCount }
    Row(modifier = Modifier.card(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatView("Recorded", Formatters.duration(totalDuration), Color.White, Modifier.weight(1f))
        StatView("Uploading", "$totalQueued", if (totalQueued > 0) AppColors.accent else AppColors.secondaryText, Modifier.weight(1f))
        StatView("Failed", "$totalFailed", if (totalFailed > 0) AppColors.warning else AppColors.secondaryText, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(session: RecordingSession, onClick: () -> Unit, onLongClick: () -> Unit) {
    val status = session.statusSummary
    Row(
        modifier = Modifier
            .clip(CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Delete session")
            .card(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(status.color.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (session.isActive) Icons.Filled.FiberManualRecord else Icons.Filled.Videocam,
                contentDescription = null,
                tint = status.color,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                Formatters.sessionDate(session.startEpochMs),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            val clips = "${session.totalCount} clip${if (session.totalCount == 1) "" else "s"}"
            Text(
                listOf(Formatters.duration(session.totalDurationSec), clips, Formatters.bytes(session.totalBytes)).joinToString(" · "),
                color = AppColors.secondaryText,
                fontSize = 12.sp
            )
            if (session.totalCount > 0 && session.uploadedCount < session.totalCount) {
                LinearProgressIndicator(
                    progress = { session.uploadFraction },
                    color = status.color,
                    trackColor = AppColors.surfaceRaised,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                )
            }
        }
        StatusBadge(status.text, status.icon, status.color)
    }
}

// ---- Session detail ----

@Composable
private fun SessionDetailScreen(graph: AppGraph, session: RecordingSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val emailJobs by graph.emailQueueManager.jobs.collectAsState()
    val jobs = emailJobs.filter { it.sessionId == session.id }
    var playingChunk by remember { mutableStateOf<VideoChunk?>(null) }
    var playingFromDrive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val title = Formatters.sessionDateLong(session.startEpochMs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .statusBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AppColors.accent)
            }
            Text(
                title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!session.isActive) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = AppColors.accent)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete Session", color = AppColors.record) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = AppColors.record) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            }
                        )
                    }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OverviewCard(
                    session = session,
                    onOpenDrive = { link ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    },
                    onPlay = { playingFromDrive = true },
                    onRetryFailed = {
                        session.chunks.filter { it.status == UploadStatus.FAILED }
                            .forEach { graph.uploadQueueManager.retryChunk(session.id, it.id) }
                    }
                )
            }
            if (jobs.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmailOutboxCard(jobs) { graph.emailQueueManager.retry(it.id) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Clips", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            if (session.chunks.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (session.isActive) "The first clip will appear here in a few seconds." else "No clips were recorded.",
                        color = AppColors.secondaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.card()
                    )
                }
            } else {
                items(session.chunks, key = { it.id }) { chunk ->
                    ChunkTile(
                        chunk = chunk,
                        onPlay = { playingChunk = chunk },
                        onRetry = { graph.uploadQueueManager.retryChunk(session.id, chunk.id) }
                    )
                }
            }
        }
    }

    playingChunk?.let { chunk -> LocalClipPlayer(chunk, onDismiss = { playingChunk = null }) }

    if (playingFromDrive) {
        session.driveFolderId?.let { folderId ->
            DrivePlaybackScreen(
                graph = graph,
                folderId = folderId,
                source = DriveSessionDownloader.Source.OWN_DRIVE,
                title = title,
                onDismiss = { playingFromDrive = false }
            )
        }
    }

    if (confirmDelete) {
        DeleteSessionDialog(graph, session, onDone = { deleted ->
            confirmDelete = false
            if (deleted) onBack()
        })
    }
}

@Composable
private fun OverviewCard(
    session: RecordingSession,
    onOpenDrive: (String) -> Unit,
    onPlay: () -> Unit,
    onRetryFailed: () -> Unit
) {
    val status = session.statusSummary
    Column(modifier = Modifier.card(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    Formatters.duration(session.totalDurationSec),
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    style = TabularNumbers
                )
                Text(
                    "${Formatters.bytes(session.totalBytes)} · ${session.chunkDurationSec.toInt()}s clips",
                    color = AppColors.secondaryText,
                    fontSize = 12.sp
                )
            }
            StatusBadge(status.text, status.icon, status.color)
        }

        Row {
            StatView("Uploaded", "${session.uploadedCount}", AppColors.success, Modifier.weight(1f))
            StatView("Queued", "${session.queuedCount}", if (session.queuedCount > 0) AppColors.accent else AppColors.secondaryText, Modifier.weight(1f))
            StatView("Failed", "${session.failedCount}", if (session.failedCount > 0) AppColors.record else AppColors.secondaryText, Modifier.weight(1f))
        }

        if (session.totalCount > 0) {
            LinearProgressIndicator(
                progress = { session.uploadFraction },
                color = status.color,
                trackColor = AppColors.surfaceRaised,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            session.driveFolderLink?.let { link ->
                Button(
                    onClick = { onOpenDrive(link) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Drive", maxLines = 1)
                }
            }
            if (session.driveFolderId != null && session.uploadedCount > 0) {
                OutlinedButton(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Play", color = AppColors.accent, maxLines = 1)
                }
            }
            if (session.failedCount > 0) {
                OutlinedButton(onClick = onRetryFailed, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = AppColors.warning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Retry ${session.failedCount}", color = AppColors.warning, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EmailOutboxCard(jobs: List<EmailJob>, onRetry: (EmailJob) -> Unit) {
    Column(modifier = Modifier.card(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Email, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text("Alert emails", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        jobs.forEach { job -> EmailJobRow(job, onRetry = { onRetry(job) }) }
    }
}

@Composable
private fun EmailJobRow(job: EmailJob, onRetry: () -> Unit) {
    val (text, icon, color) = when (job.status) {
        EmailStatus.PENDING -> if (job.attempts == 0) {
            Triple("Queued", Icons.Filled.Schedule, AppColors.warning)
        } else {
            Triple("Retrying · attempt ${job.attempts}", Icons.Filled.History, AppColors.warning)
        }
        EmailStatus.SENDING -> Triple("Sending…", Icons.AutoMirrored.Filled.Send, AppColors.accent)
        EmailStatus.SENT -> Triple("Sent ${job.lastAttemptEpochMs?.let { Formatters.time(it) } ?: ""}", Icons.Filled.CheckCircle, AppColors.success)
        EmailStatus.FAILED -> Triple("Failed after ${job.attempts} attempts", Icons.Filled.Warning, AppColors.record)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(job.subject, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$text · ${job.recipients.joinToString(", ")}",
                color = AppColors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (job.status == EmailStatus.FAILED) {
            TextButton(onClick = onRetry) { Text("Retry", color = AppColors.warning, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ---- Clip tiles ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChunkTile(chunk: VideoChunk, onPlay: () -> Unit, onRetry: () -> Unit) {
    val localFileExists = remember(chunk.localPath, chunk.status) { File(chunk.localPath).exists() }
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .aspectRatio(9f / 16f)
            .clip(shape)
            .border(1.dp, if (chunk.status == UploadStatus.FAILED) AppColors.record.copy(alpha = 0.7f) else AppColors.stroke, shape)
            .combinedClickable(
                enabled = localFileExists || chunk.status == UploadStatus.FAILED,
                onClick = { if (localFileExists) onPlay() else menuOpen = true },
                onLongClick = { menuOpen = true }
            )
            .semantics { contentDescription = "Clip ${chunk.index + 1}, ${Formatters.duration(chunk.durationSec)}, ${chunk.status.label}" }
    ) {
        ChunkThumbnail(chunk.localPath, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.5f),
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.7f)
                    )
                )
        )
        Text(
            "${chunk.index + 1}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            style = TabularNumbers,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(AppColors.overlay, CircleShape)
                .padding(5.dp)
        ) {
            Icon(chunk.status.icon, contentDescription = null, tint = chunk.status.color, modifier = Modifier.size(14.dp))
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Formatters.duration(chunk.durationSec),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TabularNumbers,
                    modifier = Modifier.weight(1f)
                )
                if (!localFileExists) {
                    Icon(Icons.Filled.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
            if (chunk.status == UploadStatus.UPLOADING) {
                LinearProgressIndicator(
                    progress = { chunk.uploadProgress.toFloat().coerceIn(0f, 1f) },
                    color = chunk.status.color,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (localFileExists) {
                DropdownMenuItem(
                    text = { Text("Play") },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    onClick = { menuOpen = false; onPlay() }
                )
            }
            if (chunk.status == UploadStatus.FAILED) {
                DropdownMenuItem(
                    text = { Text("Retry Upload") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = { menuOpen = false; onRetry() }
                )
            }
        }
    }
}

private val thumbnailCache = LruCache<String, Bitmap>(64)

/** Loads (and caches) a still frame from a local clip. */
@Composable
private fun ChunkThumbnail(path: String, modifier: Modifier) {
    var bitmap by remember(path) { mutableStateOf(thumbnailCache.get(path)) }
    LaunchedEffect(path) {
        if (bitmap != null) return@LaunchedEffect
        val generated = withContext(Dispatchers.IO) {
            if (!File(path).exists()) return@withContext null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                if (android.os.Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                } else {
                    retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                        val scale = 320f / maxOf(full.width, full.height)
                        Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
                    }
                }
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
        if (generated != null) {
            thumbnailCache.put(path, generated)
            bitmap = generated
        }
    }
    Box(modifier = modifier.background(AppColors.surfaceRaised), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Videocam, contentDescription = null, tint = AppColors.tertiaryText)
        }
    }
}

// ---- Delete ----

@Composable
private fun DeleteSessionDialog(graph: AppGraph, session: RecordingSession, onDone: (deleted: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("Delete this session?") },
        text = {
            Text(
                "Removes the clips saved on this phone and stops any pending uploads or emails. " +
                    "Clips already in Google Drive are kept."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                graph.uploadQueueManager.cancelUploads(session.id)
                graph.emailQueueManager.removeJobs(session.id)
                // App scope: the dialog leaves composition right away.
                graph.appScope.launch { graph.sessionStore.delete(session.id) }
                onDone(true)
            }) { Text("Delete Session", color = AppColors.record, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel") } }
    )
}
