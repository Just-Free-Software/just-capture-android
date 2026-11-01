package fyi.justfreesoftware.justcapture.services

import android.util.Log
import fyi.justfreesoftware.justcapture.data.SessionStore
import fyi.justfreesoftware.justcapture.data.Settings
import fyi.justfreesoftware.justcapture.model.RecordingSession
import fyi.justfreesoftware.justcapture.model.UploadStatus
import fyi.justfreesoftware.justcapture.model.VideoChunk
import fyi.justfreesoftware.justcapture.util.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ceil

/**
 * Upload queue for video chunks, mirroring the iOS app's UploadQueueManager.
 * Each chunk uploads with exponential-backoff retries; a Drive quota
 * exhaustion parks the chunk and polls the (cheap) quota endpoint with growing
 * backoff until space is freed, then retries.
 */
class UploadQueueManager(
    private val sessionStore: SessionStore,
    private val settings: Settings,
    private val authManager: GoogleAuthManager,
    private val scope: CoroutineScope
) {
    /** True when a Drive upload failed on storage quota exhaustion. */
    val storageExceeded = MutableStateFlow(false)

    private val uploadTasks = mutableMapOf<String, Job>()

    fun enqueue(sessionId: String, chunkId: String) {
        val session = sessionStore.sessions.value.firstOrNull { it.id == sessionId } ?: return
        val chunk = session.chunks.firstOrNull { it.id == chunkId } ?: return

        uploadTasks.remove(chunkId)?.cancel()
        uploadTasks[chunkId] = scope.launch {
            sessionStore.updateChunk(sessionId, chunkId) {
                it.copy(status = UploadStatus.UPLOADING, uploadProgress = 0.0)
            }

            var attempt = 0
            var quotaAttempt = 0
            while (true) {
                try {
                    val token = authManager.validAccessToken()

                    var currentSession = sessionStore.sessions.value.firstOrNull { it.id == sessionId }
                        ?: throw RuntimeException("Session $sessionId disappeared")

                    if (currentSession.driveFolderId == null) {
                        val result = GoogleDriveService.createFolder(
                            name = currentSession.folderName,
                            accessToken = token
                        )
                        sessionStore.update(sessionId) {
                            it.copy(driveFolderId = result.first, driveFolderLink = result.second)
                        }
                        currentSession = sessionStore.sessions.value.firstOrNull { it.id == sessionId }
                            ?: throw RuntimeException("Session $sessionId disappeared")
                    }

                    val folderId = currentSession.driveFolderId
                        ?: throw RuntimeException("Session has no Drive folder")

                    val fileId = GoogleDriveService.uploadFile(
                        file = File(chunk.localPath),
                        fileName = chunk.fileName,
                        folderId = folderId,
                        accessToken = token
                    ) { progress ->
                        scope.launch {
                            sessionStore.updateChunk(sessionId, chunkId) {
                                it.copy(uploadProgress = progress)
                            }
                        }
                    }

                    sessionStore.updateChunk(sessionId, chunkId) {
                        it.copy(status = UploadStatus.UPLOADED, driveFileId = fileId, uploadProgress = 1.0)
                    }
                    storageExceeded.value = false

                    if (!settings.saveChunksLocally) {
                        File(chunk.localPath).delete()
                    }

                    updatePlaylist(sessionId)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DriveStorageQuotaExceededException) {
                    // Recording keeps going; the chunk stays queued. Freeing
                    // space usually needs the user to act, so back off
                    // exponentially, and between attempts just poll the
                    // (cheap) quota endpoint instead of pushing the whole
                    // file into a 403 again.
                    sessionStore.updateChunk(sessionId, chunkId) {
                        it.copy(status = UploadStatus.PENDING, uploadProgress = 0.0)
                    }
                    storageExceeded.value = true
                    quotaAttempt += 1
                    val delaySec = storageRetryDelay(quotaAttempt)
                    Log.w(
                        TAG,
                        "Chunk ${chunk.index + 1} can't upload: Google Drive storage is full. " +
                            "Re-checking in ${delaySec.toInt() / 60} min."
                    )
                    var spaceFreed = false
                    while (true) {
                        delay((delaySec * 1000).toLong())
                        try {
                            val token = authManager.validAccessToken()
                            val quota = GoogleDriveService.getStorageQuota(token)
                            // null availableBytes = unlimited quota, always uploadable
                            if ((quota.availableBytes ?: Long.MAX_VALUE) > 0) {
                                spaceFreed = true
                                break
                            }
                        } catch (e2: CancellationException) {
                            throw e2
                        } catch (e2: Exception) {
                            // Couldn't read the quota (e.g. offline) — fall
                            // through and let the upload attempt itself
                            // surface whatever the real error is.
                            break
                        }
                    }
                    if (!spaceFreed) continue
                    // Space is back; fall through to retry the upload.
                } catch (e: Exception) {
                    attempt += 1
                    if (attempt >= RetryPolicy.MAX_ATTEMPTS) {
                        sessionStore.updateChunk(sessionId, chunkId) {
                            it.copy(status = UploadStatus.FAILED, uploadProgress = 0.0)
                        }
                        Log.e(TAG, "Chunk ${chunk.index + 1} failed after $attempt attempts: ${e.message}")
                        return@launch
                    }
                    val delaySec = RetryPolicy.delayForAttempt(attempt)
                    Log.w(
                        TAG,
                        "Upload attempt $attempt for chunk ${chunk.index + 1} failed, " +
                            "retrying in ${delaySec.toInt()}s: ${e.message}"
                    )
                    delay((delaySec * 1000).toLong())
                }
            }
        }
    }

    fun retryChunk(sessionId: String, chunkId: String) {
        enqueue(sessionId, chunkId)
    }

    /** Cancels every in-flight upload belonging to a session. */
    fun cancelUploads(sessionId: String) {
        val session = sessionStore.sessions.value.firstOrNull { it.id == sessionId } ?: return
        for (chunk in session.chunks) {
            uploadTasks.remove(chunk.id)?.cancel()
        }
    }

    /**
     * On app launch, re-enqueue every chunk that wasn't confirmed uploaded so
     * a backgrounding/kill mid-outage doesn't permanently strand uploads.
     * Chunks whose local file is already gone are skipped; PENDING/UPLOADING
     * chunks with no file are marked FAILED so the user sees the truth.
     */
    suspend fun resumePendingUploads() {
        var changed = false
        for (session in sessionStore.sessions.value) {
            for (chunk in session.chunks) {
                if (chunk.status != UploadStatus.UPLOADED) {
                    if (File(chunk.localPath).exists()) {
                        sessionStore.updateChunk(session.id, chunk.id) { it.copy(uploadProgress = 0.0) }
                        enqueue(session.id, chunk.id)
                    } else if (chunk.status == UploadStatus.PENDING || chunk.status == UploadStatus.UPLOADING) {
                        sessionStore.updateChunk(session.id, chunk.id) { it.copy(status = UploadStatus.FAILED) }
                        changed = true
                    }
                }
            }
        }
        if (changed) sessionStore.save()
    }

    suspend fun updatePlaylist(sessionId: String) {
        try {
            val session = sessionStore.sessions.value.firstOrNull { it.id == sessionId } ?: return
            val folderId = session.driveFolderId ?: return
            val token = authManager.validAccessToken()

            val content = generatePlaylistContent(session)
            val fileId = GoogleDriveService.uploadOrUpdatePlaylist(
                content = content,
                folderId = folderId,
                existingFileId = session.playlistFileId,
                accessToken = token
            )
            sessionStore.update(sessionId) { it.copy(playlistFileId = fileId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update playlist: ${e.message}")
        }
    }

    private fun generatePlaylistContent(session: RecordingSession): String {
        val m3u8 = StringBuilder()
        m3u8.append("#EXTM3U\n")
        m3u8.append("#EXT-X-VERSION:3\n")
        m3u8.append("#EXT-X-TARGETDURATION:${ceil(session.chunkDurationSec).toInt()}\n")
        m3u8.append("#EXT-X-MEDIA-SEQUENCE:0")

        for (chunk in session.chunks) {
            if (chunk.status == UploadStatus.UPLOADED && chunk.driveFileId != null) {
                m3u8.append("\n#EXTINF:${chunk.durationSec},\n")
                m3u8.append(GoogleDriveService.getDirectDownloadUrl(chunk.driveFileId))
            }
        }

        if (!session.isActive) {
            m3u8.append("\n#EXT-X-ENDLIST")
        }

        return m3u8.toString()
    }

    companion object {
        private const val TAG = "UploadQueueManager"

        // Backoff for re-checking Drive after the quota is exhausted: a few
        // minutes first, then doubling up to a generous cap (quota checks are
        // cheap; freeing space typically requires the user to act).
        const val STORAGE_RETRY_INITIAL_DELAY_SEC = 5 * 60.0
        const val STORAGE_RETRY_MAX_DELAY_SEC = 24 * 60 * 60.0
        const val STORAGE_RETRY_FACTOR = 2.0

        fun storageRetryDelay(quotaAttempt: Int): Double {
            var delay = STORAGE_RETRY_INITIAL_DELAY_SEC
            for (i in 1 until quotaAttempt.coerceAtLeast(1)) {
                delay = minOf(STORAGE_RETRY_MAX_DELAY_SEC, delay * STORAGE_RETRY_FACTOR)
            }
            return delay
        }
    }
}
