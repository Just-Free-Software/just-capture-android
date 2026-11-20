package fyi.justfreesoftware.justcapture.services

import android.util.Log
import fyi.justfreesoftware.justcapture.config.AppConfig
import fyi.justfreesoftware.justcapture.data.SessionStore
import fyi.justfreesoftware.justcapture.model.RecordingSession
import fyi.justfreesoftware.justcapture.model.UploadStatus
import fyi.justfreesoftware.justcapture.model.VideoChunk
import fyi.justfreesoftware.justcapture.util.RetryPolicy
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Upload queue for video chunks. Each chunk uploads with exponential-backoff
 * retries, continuing the same Drive upload where it left off; a Drive quota
 * exhaustion parks the chunk and polls the (cheap) quota endpoint with growing
 * backoff until the chunk fits, then retries. Being offline doesn't use up a
 * chunk's retries.
 *
 * All bookkeeping maps are touched only from [scope], which runs on the main
 * thread, so they need no locking.
 */
class UploadQueueManager(
    private val sessionStore: SessionStore,
    private val authManager: GoogleAuthManager,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope
) {
    /**
     * Set when a Drive upload fails because the account's storage quota is
     * exhausted. Observed by the UI to alert the user.
     */
    val storageExceeded = MutableStateFlow(false)

    private val _publicSharingBlocked = MutableStateFlow(false)

    /**
     * Set when the signed-in account can't share folders publicly (usually a
     * Workspace admin policy), so email links won't open for
     * recipients. Observed by the UI to warn the user ahead of time.
     */
    val publicSharingBlocked: StateFlow<Boolean> = _publicSharingBlocked.asStateFlow()

    /** Called once a session's Drive folder exists, so alerts sent without a link can send it. */
    var onFolderCreated: ((sessionId: String) -> Unit)? = null

    /** Called after each chunk lands in Drive, so the storage figure updates right away. */
    var onChunkUploaded: ((VideoChunk) -> Unit)? = null

    private val uploadTasks = mutableMapOf<String, Job>()

    /** Sessions with a [prepareFolder] loop running, so repeated calls don't each start their own. */
    private val folderPreparers = mutableSetOf<String>()

    /**
     * Every clip waiting on storage re-checks on its own timer, so they share
     * one quota request per [SHARED_QUOTA_CHECK_INTERVAL_MS]. A long backlog
     * would otherwise send a request per clip.
     */
    private var lastQuotaCheck: Pair<Long, Long?>? = null
    private var quotaCheckTask: Deferred<Long?>? = null

    /**
     * One in-flight Drive folder creation per session. Without this, the
     * recording start and the first chunk uploads each create their own
     * folder and the session's clips end up split across several folders.
     */
    private val folderTasks = mutableMapOf<String, Deferred<String>>()

    /** One in-flight sharing request per session. */
    private val shareTasks = mutableMapOf<String, Deferred<Unit>>()

    /** Serializes each session's playlist and location writes so only one of each file is ever created. */
    private val writeLocks = mutableMapOf<String, Mutex>()

    /**
     * Caps simultaneous file uploads. After an outage dozens of chunks may be
     * queued; pushing them all at once on a weak connection makes every one
     * of them slower to land.
     */
    private val uploadSlots = Semaphore(AppConfig.MAX_CONCURRENT_UPLOADS)

    /**
     * Returns the session's Drive folder id, creating the folder if needed.
     * Concurrent callers share a single creation request.
     */
    suspend fun ensureFolder(sessionId: String): String {
        val session = sessionStore.session(sessionId) ?: throw IllegalStateException("Session $sessionId is gone")
        session.driveFolderId?.let { id ->
            if (session.driveFolderShared == false) shareFolder(sessionId)
            return id
        }
        // A finished task (failed, or done before the session saw its id) is
        // replaced rather than reused.
        val task = folderTasks[sessionId]?.takeIf { it.isActive } ?: scope.async {
            val token = authManager.validAccessToken()
            val (folderId, link) = GoogleDriveService.createFolder(session.folderName, token)
            sessionStore.update(sessionId) { it.copy(driveFolderId = folderId, driveFolderLink = link) }
            if (sessionStore.session(sessionId)?.driveFolderShared == false) shareFolder(sessionId)
            // After sharing, so a follow-up link works when it's opened.
            onFolderCreated?.invoke(sessionId)
            folderId
        }.also { folderTasks[sessionId] = it }
        return task.await()
    }

    /**
     * Marks the session's folder for link sharing because an email is about to
     * link to it. Folders stay private until this is called. If the folder
     * doesn't exist yet, it's shared when [ensureFolder] creates it.
     */
    suspend fun requestLinkSharing(sessionId: String) {
        val session = sessionStore.session(sessionId) ?: return
        if (session.driveFolderShared != null) return
        sessionStore.update(sessionId) { it.copy(driveFolderShared = false) }
        if (session.driveFolderId != null) scope.launch { shareFolder(sessionId) }
    }

    /**
     * Shares the session's folder with anyone who has the link. Best-effort:
     * a failure (outage, or a Workspace policy that forbids public links)
     * must never block uploads, so it's logged and retried on the next
     * [ensureFolder] call instead of thrown.
     */
    private suspend fun shareFolder(sessionId: String) {
        val task = shareTasks[sessionId]?.takeIf { it.isActive } ?: scope.async {
            try {
                val folderId = sessionStore.session(sessionId)?.driveFolderId ?: return@async
                val token = authManager.validAccessToken()
                GoogleDriveService.shareWithAnyoneWithLink(folderId, token)
                sessionStore.update(sessionId) { it.copy(driveFolderShared = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveException.PublicSharingNotAllowed) {
                // Retrying can't succeed; stop and leave the folder private.
                _publicSharingBlocked.value = true
                sessionStore.update(sessionId) { it.copy(driveFolderShared = null) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to share Drive folder: ${e.message}")
            }
        }.also { shareTasks[sessionId] = it }
        task.await()
    }

    /**
     * Finds out at sign-in whether this account may share publicly, so the
     * user is warned before an emergency rather than during one. Drive has no
     * way to ask, so this shares a throwaway folder and deletes it. A network
     * failure leaves the previous answer in place.
     */
    suspend fun checkPublicSharing() {
        try {
            val token = authManager.validAccessToken()
            val (probeId, _) = GoogleDriveService.createFolder("Just Capture sharing check", token)
            try {
                GoogleDriveService.shareWithAnyoneWithLink(probeId, token)
                _publicSharingBlocked.value = false
            } catch (e: DriveException.PublicSharingNotAllowed) {
                _publicSharingBlocked.value = true
            } finally {
                withContext(NonCancellable) {
                    runCatching { GoogleDriveService.deleteFile(probeId, token) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Public sharing check failed: ${e.message}")
        }
    }

    /** Clears per-account state when the user signs out. */
    fun resetAccountState() {
        _publicSharingBlocked.value = false
        lastQuotaCheck = null
    }

    /**
     * Free Drive space (null means unlimited), from a recent check if there
     * is one. Failed checks aren't remembered, so the caller sees the error.
     */
    private suspend fun sharedAvailableBytes(): Long? {
        lastQuotaCheck?.let { (checkedAtMs, available) ->
            if (System.currentTimeMillis() - checkedAtMs < SHARED_QUOTA_CHECK_INTERVAL_MS) return available
        }
        val task = quotaCheckTask?.takeIf { it.isActive } ?: scope.async {
            val available = GoogleDriveService.getStorageQuota(authManager.validAccessToken()).availableBytes
            lastQuotaCheck = System.currentTimeMillis() to available
            available
        }.also { quotaCheckTask = it }
        return task.await()
    }

    /**
     * Creates the session's Drive folder right away (so the alert email can
     * carry the link), retrying with backoff. Failures are non-fatal — the
     * first chunk upload will try again.
     */
    fun prepareFolder(sessionId: String) {
        if (sessionStore.session(sessionId)?.driveFolderId != null || !folderPreparers.add(sessionId)) return
        scope.launch {
            try {
                var attempt = 0
                while (sessionStore.session(sessionId)?.let { it.driveFolderId == null } == true) {
                    try {
                        ensureFolder(sessionId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (network.isOffline(e)) {
                            network.waitForRestoredConnection(RetryPolicy.MAX_DELAY_MS)
                            continue
                        }
                        attempt += 1
                        if (attempt >= RetryPolicy.MAX_ATTEMPTS) {
                            Log.e(TAG, "Failed to create Drive folder after $attempt attempts: ${e.message}")
                            return@launch
                        }
                        val delaySec = RetryPolicy.delayForAttempt(attempt)
                        Log.w(
                            TAG,
                            "Drive folder attempt $attempt failed, retrying in ${delaySec.toInt()}s: ${e.message}"
                        )
                        network.waitForRestoredConnection((delaySec * 1000).toLong())
                    }
                }
            } finally {
                folderPreparers.remove(sessionId)
            }
        }
    }

    fun enqueue(sessionId: String, chunkId: String) {
        uploadTasks.remove(chunkId)?.cancel()
        sessionStore.updateChunkInMemory(sessionId, chunkId) {
            it.copy(status = UploadStatus.PENDING, uploadProgress = 0.0)
        }
        uploadTasks[chunkId] = scope.launch {
            var quotaAttempt = 0
            while (true) {
                val chunk = sessionStore.session(sessionId)?.chunks?.firstOrNull { it.id == chunkId } ?: return@launch
                try {
                    val fileId = uploadSlots.withPermit { upload(sessionId, chunk) }
                    sessionStore.updateChunk(sessionId, chunkId) {
                        it.copy(
                            status = UploadStatus.UPLOADED,
                            driveFileId = fileId,
                            uploadProgress = 1.0,
                            uploadSessionUrl = null,
                            uploadAttempts = 0
                        )
                    }
                    storageExceeded.value = false
                    onChunkUploaded?.invoke(chunk)
                    uploadTasks.remove(chunkId)
                    updatePlaylist(sessionId)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DriveException.StorageQuotaExceeded) {
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
                    waitForStorage(chunk, storageRetryDelay(quotaAttempt))
                } catch (e: Exception) {
                    if (network.isOffline(e)) {
                        // Doesn't use up a try.
                        sessionStore.updateChunkInMemory(sessionId, chunkId) {
                            it.copy(status = UploadStatus.PENDING, uploadProgress = 0.0)
                        }
                        Log.i(TAG, "Chunk ${chunk.index + 1} is waiting for a connection.")
                        network.waitForRestoredConnection(RetryPolicy.MAX_DELAY_MS)
                        continue
                    }
                    // Saved, so a clip that keeps failing across launches still runs out of tries.
                    val attempt = chunk.uploadAttempts + 1
                    val failed = attempt >= RetryPolicy.MAX_ATTEMPTS
                    sessionStore.updateChunk(sessionId, chunkId) {
                        it.copy(
                            status = if (failed) UploadStatus.FAILED else UploadStatus.PENDING,
                            uploadProgress = 0.0,
                            uploadAttempts = attempt
                        )
                    }
                    if (failed) {
                        Log.e(TAG, "Chunk ${chunk.index + 1} failed after $attempt attempts: ${e.message}")
                        uploadTasks.remove(chunkId)
                        return@launch
                    }
                    val delaySec = RetryPolicy.delayForAttempt(attempt)
                    Log.w(
                        TAG,
                        "Upload attempt $attempt for chunk ${chunk.index + 1} failed, retrying in ${delaySec.toInt()}s: ${e.message}"
                    )
                    network.waitForRestoredConnection((delaySec * 1000).toLong())
                }
            }
        }
    }

    /**
     * Sends one clip, continuing an earlier upload of it if Drive still has
     * one. Returns the file's id, which may come straight from Drive if the
     * whole clip had already arrived.
     */
    private suspend fun upload(sessionId: String, chunk: VideoChunk): String {
        sessionStore.updateChunkInMemory(sessionId, chunk.id) { it.copy(status = UploadStatus.UPLOADING) }
        val folderId = ensureFolder(sessionId)
        val file = File(chunk.localPath)
        val fileSize = file.length()

        var offset = 0L
        var sessionUrl = chunk.uploadSessionUrl
        if (sessionUrl != null) {
            when (val state = GoogleDriveService.resumableUploadState(sessionUrl, fileSize)) {
                is GoogleDriveService.ResumableUploadState.Complete -> return state.fileId
                is GoogleDriveService.ResumableUploadState.Incomplete -> offset = state.nextOffset
                GoogleDriveService.ResumableUploadState.Expired -> sessionUrl = null
            }
        }
        val uploadUrl = sessionUrl ?: GoogleDriveService.createUploadSession(
            chunk.fileName,
            fileSize,
            folderId,
            authManager.validAccessToken()
        ).also { created ->
            sessionStore.updateChunk(sessionId, chunk.id) { it.copy(uploadSessionUrl = created) }
        }
        return GoogleDriveService.uploadFile(file, uploadUrl, offset) { progress ->
            scope.launch {
                sessionStore.updateChunkInMemory(sessionId, chunk.id) { it.copy(uploadProgress = progress) }
            }
        }
    }

    /** Waits, re-checking Drive every [delaySec], until the clip fits or the quota can't be read. */
    private suspend fun waitForStorage(chunk: VideoChunk, delaySec: Double) {
        Log.w(TAG, "Chunk ${chunk.index + 1} can't upload: Drive is full. Re-checking in ${delaySec.toInt() / 60} min.")
        while (true) {
            delay((delaySec * 1000).toLong())
            try {
                // null = unlimited quota, always uploadable. Wait until this
                // clip fits: a clip that can't fit would only be rejected again.
                if ((sharedAvailableBytes() ?: Long.MAX_VALUE) >= maxOf(chunk.fileSizeBytes, 1L)) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Couldn't read the quota (e.g. offline) — let the upload
                // attempt itself surface the real error.
                Log.w(TAG, "Couldn't check Drive storage: ${e.message}")
                return
            }
        }
    }

    fun retryChunk(sessionId: String, chunkId: String) {
        sessionStore.updateChunkInMemory(sessionId, chunkId) { it.copy(uploadAttempts = 0) }
        enqueue(sessionId, chunkId)
    }

    /** Cancels every in-flight upload belonging to a session, e.g. before deleting it. */
    fun cancelUploads(sessionId: String) {
        val session = sessionStore.session(sessionId) ?: return
        for (chunk in session.chunks) {
            uploadTasks.remove(chunk.id)?.cancel()
        }
    }

    /**
     * Sweeps up every chunk that wasn't confirmed uploaded and isn't being
     * worked on, so a kill or outage can't strand it. PENDING/UPLOADING chunks
     * whose file is gone are marked FAILED so the user sees the truth.
     * [retryFailed] also gives clips that ran out of tries a fresh set, and
     * retries location files. The caller limits how often that happens.
     */
    suspend fun resumePendingUploads(retryFailed: Boolean) {
        for (session in sessionStore.sessions.value) {
            session.chunks
                .filter { it.status != UploadStatus.UPLOADED && uploadTasks[it.id]?.isActive != true }
                .forEach { resume(session.id, it, retryFailed) }
        }
        if (!retryFailed) return
        // Clips may all be up already, leaving nothing else to retry this.
        for (session in sessionStore.sessions.value) {
            if (session.locationText != session.uploadedLocationText) {
                scope.launch { updateLocationFile(session.id) }
            }
        }
    }

    private suspend fun resume(sessionId: String, chunk: VideoChunk, retryFailed: Boolean) {
        val wasFailed = chunk.status == UploadStatus.FAILED
        if (!File(chunk.localPath).exists()) {
            if (!wasFailed) {
                sessionStore.updateChunk(sessionId, chunk.id) { it.copy(status = UploadStatus.FAILED) }
            }
        } else if (!wasFailed) {
            enqueue(sessionId, chunk.id)
        } else if (retryFailed) {
            retryChunk(sessionId, chunk.id)
        }
    }

    /** Uploads are running or waiting to retry. */
    val hasActiveWork: Boolean
        get() = uploadTasks.values.any { it.isActive }

    /** Clips not yet on Drive that this phone still has. */
    val hasUnfinishedClips: Boolean
        get() = sessionStore.sessions.value.any { session ->
            session.chunks.any { it.status != UploadStatus.UPLOADED && File(it.localPath).exists() }
        }

    /**
     * Rewrites the session's playlist on Drive. Calls for the same session
     * run one after another so only one playlist file is ever created. Also
     * retries the location file, so a failed upload of it is picked up the
     * next time any clip finishes uploading.
     */
    suspend fun updatePlaylist(sessionId: String) {
        lockFor(sessionId).withLock {
            writePlaylist(sessionId)
            writeLocationFile(sessionId)
        }
    }

    /**
     * Uploads the session's location as soon as it's known, without waiting
     * for the first clip, in case the phone is lost before that clip uploads.
     */
    suspend fun updateLocationFile(sessionId: String) {
        try {
            ensureFolder(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Picked up again after the next clip uploads, or at next launch.
            Log.w(TAG, "No Drive folder for location file yet: ${e.message}")
            return
        }
        lockFor(sessionId).withLock { writeLocationFile(sessionId) }
    }

    private fun lockFor(sessionId: String) = writeLocks.getOrPut(sessionId) { Mutex() }

    private suspend fun writePlaylist(sessionId: String) {
        try {
            val session = sessionStore.session(sessionId) ?: return
            val folderId = session.driveFolderId ?: return
            val token = authManager.validAccessToken()
            val fileId = GoogleDriveService.uploadOrUpdatePlaylist(
                content = playlistContent(session),
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

    private suspend fun writeLocationFile(sessionId: String) {
        try {
            val session = sessionStore.session(sessionId) ?: return
            val content = session.locationText ?: return
            if (content == session.uploadedLocationText) return
            val folderId = session.driveFolderId ?: return
            val token = authManager.validAccessToken()
            val fileId = GoogleDriveService.uploadOrUpdateTextFile(
                name = "location.txt",
                mimeType = "text/plain",
                content = content,
                folderId = folderId,
                existingFileId = session.locationFileId,
                accessToken = token
            )
            sessionStore.update(sessionId) { it.copy(locationFileId = fileId, uploadedLocationText = content) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upload location: ${e.message}")
        }
    }

    private fun playlistContent(session: RecordingSession): String = buildString {
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:3\n")
        append("#EXT-X-TARGETDURATION:${ceil(session.chunkDurationSec).toInt()}\n")
        append("#EXT-X-MEDIA-SEQUENCE:0")
        for (chunk in session.chunks) {
            val fileId = chunk.driveFileId
            if (chunk.status == UploadStatus.UPLOADED && fileId != null) {
                append("\n#EXTINF:${chunk.durationSec},\n")
                append(GoogleDriveService.getDirectDownloadUrl(fileId))
            }
        }
        if (!session.isActive) append("\n#EXT-X-ENDLIST")
    }

    companion object {
        private const val TAG = "UploadQueueManager"

        // Backoff for re-checking Drive after the quota is exhausted: a few
        // minutes first, then doubling up to a cap, so clips start uploading
        // soon after someone frees up space.
        const val STORAGE_RETRY_INITIAL_DELAY_SEC = 5 * 60.0
        const val STORAGE_RETRY_MAX_DELAY_SEC = 15 * 60.0
        const val STORAGE_RETRY_FACTOR = 2.0
        const val SHARED_QUOTA_CHECK_INTERVAL_MS = 5 * 60_000L

        fun storageRetryDelay(quotaAttempt: Int): Double {
            var delay = STORAGE_RETRY_INITIAL_DELAY_SEC
            for (i in 1 until quotaAttempt.coerceAtLeast(1)) {
                delay = minOf(STORAGE_RETRY_MAX_DELAY_SEC, delay * STORAGE_RETRY_FACTOR)
            }
            return delay
        }
    }
}
