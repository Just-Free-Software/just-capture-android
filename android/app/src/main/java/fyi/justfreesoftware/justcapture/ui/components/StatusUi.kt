package fyi.justfreesoftware.justcapture.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import fyi.justfreesoftware.justcapture.model.RecordingSession
import fyi.justfreesoftware.justcapture.model.UploadStatus
import fyi.justfreesoftware.justcapture.ui.theme.AppColors

val UploadStatus.color: Color
    get() = when (this) {
        UploadStatus.PENDING -> AppColors.warning
        UploadStatus.UPLOADING -> AppColors.accent
        UploadStatus.UPLOADED -> AppColors.success
        UploadStatus.FAILED -> AppColors.record
    }

val UploadStatus.label: String
    get() = when (this) {
        UploadStatus.PENDING -> "Queued"
        UploadStatus.UPLOADING -> "Uploading"
        UploadStatus.UPLOADED -> "Uploaded"
        UploadStatus.FAILED -> "Failed"
    }

val UploadStatus.icon: ImageVector
    get() = when (this) {
        UploadStatus.PENDING -> Icons.Filled.Schedule
        UploadStatus.UPLOADING -> Icons.Filled.ArrowCircleUp
        UploadStatus.UPLOADED -> Icons.Filled.CheckCircle
        UploadStatus.FAILED -> Icons.Filled.Warning
    }

data class StatusSummary(val text: String, val icon: ImageVector, val color: Color)

/** One-word summary used by list rows and badges. */
val RecordingSession.statusSummary: StatusSummary
    get() = when {
        isActive -> StatusSummary("Live", Icons.Filled.FiberManualRecord, AppColors.record)
        totalCount == 0 -> StatusSummary("Empty", Icons.Filled.Inbox, AppColors.tertiaryText)
        failedCount > 0 -> StatusSummary("$failedCount failed", Icons.Filled.Warning, AppColors.warning)
        queuedCount > 0 -> StatusSummary("Uploading", Icons.Filled.ArrowCircleUp, AppColors.accent)
        else -> StatusSummary("Backed up", Icons.Filled.CloudDone, AppColors.success)
    }
