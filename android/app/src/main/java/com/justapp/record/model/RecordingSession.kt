package com.justapp.record.model

import kotlinx.serialization.Serializable

@Serializable
enum class UploadStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED
}

@Serializable
data class VideoChunk(
    val id: String,
    val index: Int,
    val localPath: String,
    val createdAtEpochMs: Long,
    val durationSec: Double = 0.0,
    val fileSizeBytes: Long = 0,
    val status: UploadStatus = UploadStatus.PENDING,
    val uploadProgress: Double = 0.0,
    val driveFileId: String? = null
) {
    val fileName: String
        get() = localPath.substringAfterLast('/')
}

@Serializable
data class RecordingSession(
    val id: String,
    val startEpochMs: Long,
    val chunks: List<VideoChunk> = emptyList(),
    val driveFolderId: String? = null,
    val driveFolderLink: String? = null,
    val locationAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val chunkDurationSec: Double = 30.0,
    val isActive: Boolean = true,
    val playlistFileId: String? = null
) {
    val folderName: String
        get() = "Session $id"

    val uploadedCount: Int
        get() = chunks.count { it.status == UploadStatus.UPLOADED }

    val failedCount: Int
        get() = chunks.count { it.status == UploadStatus.FAILED }

    val totalCount: Int
        get() = chunks.size

    companion object {
        fun new(chunkDurationSec: Double): RecordingSession {
            val now = System.currentTimeMillis()
            val formatter = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            return RecordingSession(
                id = formatter.format(java.util.Date(now)),
                startEpochMs = now,
                chunkDurationSec = chunkDurationSec
            )
        }
    }
}
