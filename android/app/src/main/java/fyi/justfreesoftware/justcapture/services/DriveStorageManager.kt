package fyi.justfreesoftware.justcapture.services

import android.util.Log
import fyi.justfreesoftware.justcapture.config.AppConfig
import fyi.justfreesoftware.justcapture.data.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tracks the Google Drive storage quota for the signed-in account. */
class DriveStorageManager(
    private val sessionStore: SessionStore,
    private val authManager: GoogleAuthManager,
    private val scope: CoroutineScope
) {
    enum class StorageState { OK, LOW, OUT, UNLIMITED, UNAVAILABLE }

    companion object {
        const val LOW_STORAGE_THRESHOLD_BYTES = 250L * 1024 * 1024
        const val QUOTA_REFRESH_INTERVAL_MS = 60_000L

        /**
         * Drive can take a minute or two to count newly uploaded files in the
         * quota. Within this long of our last upload, a server figure lower
         * than our own running total is treated as stale.
         */
        const val QUOTA_LAG_WINDOW_MS = 120_000L
        private const val TAG = "DriveStorageManager"
    }

    private val _quota = MutableStateFlow<DriveStorageQuota?>(null)
    val quota: StateFlow<DriveStorageQuota?> = _quota.asStateFlow()

    private val _forcedOutOfStorage = MutableStateFlow(false)
    val forcedOutOfStorage: StateFlow<Boolean> = _forcedOutOfStorage.asStateFlow()

    /**
     * Set on every attempt, successful or not, so a failing quota request is
     * still throttled to once per [QUOTA_REFRESH_INTERVAL_MS].
     */
    private var lastRefreshAttemptMs: Long? = null
    private var lastLocalUploadMs: Long? = null
    private val refreshMutex = Mutex()

    fun state(): StorageState = state(_quota.value, _forcedOutOfStorage.value)

    fun state(quota: DriveStorageQuota?, forcedOut: Boolean): StorageState {
        if (forcedOut) return StorageState.OUT
        val q = quota ?: return StorageState.UNAVAILABLE
        val available = q.availableBytes ?: return StorageState.UNLIMITED
        if (available <= 0) return StorageState.OUT
        if (available < LOW_STORAGE_THRESHOLD_BYTES) return StorageState.LOW
        return StorageState.OK
    }

    fun availableBytes(): Long? = _quota.value?.availableBytes

    fun bytesPerSecondEstimate(): Double {
        var totalBytes = 0.0
        var totalSeconds = 0.0
        for (session in sessionStore.sessions.value) {
            for (chunk in session.chunks) {
                if (chunk.durationSec > 0 && chunk.fileSizeBytes > 0) {
                    totalBytes += chunk.fileSizeBytes
                    totalSeconds += chunk.durationSec
                }
            }
        }
        return if (totalSeconds > 0) totalBytes / totalSeconds else AppConfig.ESTIMATED_VIDEO_BYTES_PER_SEC
    }

    fun estimatedRecordingSeconds(): Double? {
        val available = availableBytes() ?: return null
        if (available <= 0) return null
        return available / bytesPerSecondEstimate()
    }

    suspend fun refresh() {
        if (refreshMutex.isLocked) return
        refreshMutex.withLock {
            lastRefreshAttemptMs = System.currentTimeMillis()
            try {
                val server = GoogleDriveService.getStorageQuota(authManager.validAccessToken())
                val local = _quota.value
                val lastUpload = lastLocalUploadMs
                _quota.value = if (local != null && lastUpload != null &&
                    System.currentTimeMillis() - lastUpload < QUOTA_LAG_WINDOW_MS &&
                    local.limit == server.limit && local.usage > server.usage
                ) {
                    // Drive hasn't counted our latest uploads yet; don't let
                    // the free space jump back up.
                    DriveStorageQuota(limit = server.limit, usage = local.usage)
                } else {
                    server
                }
                _forcedOutOfStorage.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch Drive storage quota: ${e.message}")
            }
        }
    }

    suspend fun refreshIfStale() {
        val last = lastRefreshAttemptMs
        if (last != null && System.currentTimeMillis() - last < QUOTA_REFRESH_INTERVAL_MS) return
        refresh()
    }

    /**
     * Counts a finished upload against the quota right away, rather than
     * waiting for the next refresh, then re-checks with Drive if the last
     * check is more than a minute old.
     */
    fun recordUpload(bytes: Long) {
        _forcedOutOfStorage.value = false
        _quota.value?.let { _quota.value = it.copy(usage = it.usage + bytes) }
        lastLocalUploadMs = System.currentTimeMillis()
        scope.launch { refreshIfStale() }
    }

    fun markOutOfStorage() {
        _forcedOutOfStorage.value = true
    }

    /** Clears the previous account's figures when the user signs out. */
    fun reset() {
        _quota.value = null
        _forcedOutOfStorage.value = false
        lastRefreshAttemptMs = null
        lastLocalUploadMs = null
    }
}
