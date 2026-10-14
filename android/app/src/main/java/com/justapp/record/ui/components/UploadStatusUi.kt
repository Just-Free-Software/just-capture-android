package com.justapp.record.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import com.justapp.record.model.UploadStatus

/** UI helpers for UploadStatus, mirroring the iOS app's UploadStatus+UI.swift. */

val UploadStatus.color: Color
    get() = when (this) {
        UploadStatus.PENDING -> Color(0xFFFFA726)
        UploadStatus.UPLOADING -> Color(0xFF42A5F5)
        UploadStatus.UPLOADED -> Color(0xFF66BB6A)
        UploadStatus.FAILED -> Color(0xFFEF5350)
    }

val UploadStatus.label: String
    get() = when (this) {
        UploadStatus.PENDING -> "Pending"
        UploadStatus.UPLOADING -> "Uploading"
        UploadStatus.UPLOADED -> "Uploaded"
        UploadStatus.FAILED -> "Failed"
    }

val UploadStatus.icon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        UploadStatus.PENDING -> Icons.Filled.Schedule
        UploadStatus.UPLOADING -> Icons.Filled.ArrowCircleUp
        UploadStatus.UPLOADED -> Icons.Filled.CheckCircle
        UploadStatus.FAILED -> Icons.Filled.Warning
    }
