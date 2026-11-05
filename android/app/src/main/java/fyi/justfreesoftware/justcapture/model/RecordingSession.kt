package fyi.justfreesoftware.justcapture.model

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /**
     * Whether the Drive folder has been shared with anyone who has the link.
     * `false` means sharing is still owed and will be retried; `null` means
     * the folder is left private (the account's policy forbids public links).
     */
    val driveFolderShared: Boolean? = false,
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

    val queuedCount: Int
        get() = chunks.count { it.status == UploadStatus.PENDING || it.status == UploadStatus.UPLOADING }

    val totalCount: Int
        get() = chunks.size

    val totalDurationSec: Double
        get() = chunks.sumOf { it.durationSec }

    val totalBytes: Long
        get() = chunks.sumOf { it.fileSizeBytes }

    val uploadFraction: Float
        get() = if (totalCount == 0) 0f else uploadedCount.toFloat() / totalCount

    companion object {
        private var lastIssuedBase: String? = null
        private var collisionCount = 0

        fun new(chunkDurationSec: Double): RecordingSession {
            val now = System.currentTimeMillis()
            // Ids have one-second resolution; a stop-and-restart within the
            // same second would otherwise reuse the previous session's id and
            // folder.
            var id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
            synchronized(this) {
                if (id == lastIssuedBase) {
                    collisionCount += 1
                    id += "_${collisionCount + 1}"
                } else {
                    lastIssuedBase = id
                    collisionCount = 0
                }
            }
            return RecordingSession(
                id = id,
                startEpochMs = now,
                chunkDurationSec = chunkDurationSec
            )
        }
    }
}
