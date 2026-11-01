package fyi.justfreesoftware.justcapture.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.justfreesoftware.justcapture.AppGraph
import fyi.justfreesoftware.justcapture.model.EmailJob
import fyi.justfreesoftware.justcapture.model.EmailStatus
import fyi.justfreesoftware.justcapture.model.RecordingSession
import fyi.justfreesoftware.justcapture.model.VideoChunk
import fyi.justfreesoftware.justcapture.ui.components.StatusCard
import fyi.justfreesoftware.justcapture.util.Formatters
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(graph: AppGraph) {
    val sessions by graph.sessionStore.sessions.collectAsState()
    val emailJobs by graph.emailQueueManager.jobs.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var sessionToDelete by remember { mutableStateOf<RecordingSession?>(null) }
    var playingChunk by remember { mutableStateOf<VideoChunk?>(null) }

    val displaySession = sessions.firstOrNull { it.id == selectedSessionId }
        ?: graph.sessionStore.currentSession

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (sessions.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SessionChipRow(
                        sessions = sessions,
                        selectedId = displaySession?.id,
                        onSelect = { selectedSessionId = it.id }
                    )
                }

                if (displaySession != null) {
                    val session = displaySession
                    item {
                        SessionInfoCard(
                            session = session,
                            onRequestDelete = { sessionToDelete = session }
                        )
                    }

                    val jobsForSession = emailJobs.filter { it.sessionId == session.id }
                    if (jobsForSession.isNotEmpty()) {
                        item {
                            EmailOutboxCard(
                                jobs = jobsForSession,
                                onRetry = { graph.emailQueueManager.retry(it.id) },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    if (session.chunks.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VideocamOff,
                                    contentDescription = null,
                                    tint = Color(0xFF888888),
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    "No chunks recorded yet",
                                    color = Color(0xFF888888),
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    } else {
                        items(session.chunks, key = { it.id }) { chunk ->
                            StatusCard(
                                chunk = chunk,
                                onRetry = { graph.uploadQueueManager.retryChunk(session.id, chunk.id) },
                                onPlay = { playingChunk = chunk },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (playingChunk != null) {
        VideoPlayerDialog(chunk = playingChunk!!, onDismiss = { playingChunk = null })
    }

    if (sessionToDelete != null) {
        val session = sessionToDelete!!
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Session?") },
            text = {
                Text(
                    "This removes the session, its locally saved recordings, related email jobs, " +
                        "and cancels any in-progress uploads. Files already uploaded to Google Drive " +
                        "are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        graph.uploadQueueManager.cancelUploads(session.id)
                        graph.emailQueueManager.removeJobs(session.id)
                        scope.launch { graph.sessionStore.delete(session.id) }
                        if (selectedSessionId == session.id) selectedSessionId = null
                        sessionToDelete = null
                    }
                ) { Text("Delete", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Videocam,
            contentDescription = null,
            tint = Color(0x80888888),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No Sessions Yet",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Start recording to see upload status here",
            color = Color(0xFF888888),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SessionChipRow(
    sessions: List<RecordingSession>,
    selectedId: String?,
    onSelect: (RecordingSession) -> Unit
) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(sessions, key = { it.id }) { session ->
            SessionChip(
                session = session,
                isSelected = session.id == selectedId,
                onClick = { onSelect(session) }
            )
        }
    }
}

@Composable
private fun SessionChip(
    session: RecordingSession,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val statusText: String = when {
        session.isActive -> "Recording"
        session.totalCount == 0 -> "Empty"
        else -> "${session.uploadedCount}/${session.totalCount}"
    }
    val statusColor: Color = when {
        session.isActive -> Color(0xFFEF5350)
        session.failedCount > 0 -> Color(0xFFFFA726)
        session.totalCount > 0 && session.uploadedCount == session.totalCount -> Color(0xFF66BB6A)
        else -> Color(0xFFAAAAAA)
    }

    Column(
        modifier = Modifier
            .background(
                if (isSelected) Color(0x402196F3) else Color(0x0DFFFFFF),
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = Formatters.formatSessionDate(session.startEpochMs),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (session.isActive) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(statusColor, CircleShape)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(9.dp)
                )
            }
            Text(
                text = statusText,
                color = statusColor,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun SessionInfoCard(
    session: RecordingSession,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Color(0xE6161616), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Session Info",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRequestDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete session",
                    tint = Color(0xFFEF5350)
                )
            }
        }

        Text(
            text = Formatters.formatSessionDate(session.startEpochMs),
            color = Color(0xFFAAAAAA),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Stat("Total", "${session.totalCount}", Color(0xFF42A5F5))
            Stat("Uploaded", "${session.uploadedCount}", Color(0xFF66BB6A))
            Stat("Failed", "${session.failedCount}", Color(0xFFEF5350))
        }

        if (!session.driveFolderLink.isNullOrEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x332196F3), RoundedCornerShape(10.dp))
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(session.driveFolderLink))
                            )
                        }
                    }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = Color(0xFF42A5F5)
                )
                Spacer(Modifier.width(8.dp))
                Text("Open in Drive", color = Color(0xFF42A5F5))
            }
        }
    }
}

@Composable
private fun Stat(title: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            color = Color(0xFFAAAAAA),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
        Text(
            value,
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EmailOutboxCard(
    jobs: List<EmailJob>,
    onRetry: (EmailJob) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE6161616), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Alert Email",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = null,
                tint = Color(0xFF42A5F5)
            )
        }

        for (job in jobs) {
            EmailJobRow(job = job, onRetry = { onRetry(job) })
        }
    }
}

@Composable
private fun EmailJobRow(job: EmailJob, onRetry: () -> Unit) {
    val statusText: String = when (job.status) {
        EmailStatus.PENDING ->
            if (job.attempts == 0) "Queued" else "Retrying (attempt ${job.attempts})"
        EmailStatus.SENDING -> "Sending…"
        EmailStatus.SENT -> "Sent"
        EmailStatus.FAILED -> "Failed after ${job.attempts} attempts"
    }
    val statusColor: Color = when (job.status) {
        EmailStatus.PENDING -> Color(0xFFFFA726)
        EmailStatus.SENDING -> Color(0xFF42A5F5)
        EmailStatus.SENT -> Color(0xFF66BB6A)
        EmailStatus.FAILED -> Color(0xFFEF5350)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (job.status == EmailStatus.SENT) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = job.recipients.joinToString(", "),
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusText,
                color = statusColor,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
            )
        }

        if (job.status == EmailStatus.FAILED) {
            IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Retry email",
                    tint = Color(0xFFFFA726),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
