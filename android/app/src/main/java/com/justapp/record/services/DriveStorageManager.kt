package com.justapp.record.services

import android.content.Context
import android.util.Log
import com.justapp.record.config.AppConfig
import com.justapp.record.data.SessionStore
import com.justapp.record.util.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks the Google Drive storage quota for the signed-in account, mirroring
 * the iOS app's DriveStorageManager.
 */
class DriveStorageManager(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val authManager: GoogleAuthManager
) {
    enum class StorageState { OK, LOW, OUT, UNLIMITED, UNAVAILABLE }

    companion object {
        const val LOW_STORAGE_THRESHOLD_BYTES = 250L * 1024 * 1024
        const val QUOTA_REFRESH_INTERVAL_MS = 60_000L
    }

    val quota = MutableStateFlow<DriveStorageQuota?>(null)
    val forcedOutOfStorage = MutableStateFlow(false)
    val lastRefreshedMs = MutableStateFlow<Long?>(null)

    private val refreshMutex = Mutex()

    fun state(): StorageState {
        if (forcedOutOfStorage.value) return StorageState.OUT
        val q = quota.value ?: return StorageState.UNAVAILABLE
        val available = q.availableBytes ?: return StorageState.UNLIMITED
        if (available <= 0) return StorageState.OUT
        if (available < LOW_STORAGE_THRESHOLD_BYTES) return StorageState.LOW
        return StorageState.OK
    }

    fun availableBytes(): Long? = quota.value?.availableBytes

    fun bytesPerSecondEstimate(): Double {
        var totalBytes = 0.0
        var totalSeconds = 0.0
        for (session in sessionStore.sessions.value) {
            for (chunk in session.chunks) {
                if (chunk.durationSec > 0 && chunk.fileSizeBytes > 0) {
                    totalBytes += chunk.fileSizeBytes.toDouble()
                    totalSeconds += chunk.durationSec
                }
            }
        }
        return if (totalSeconds > 0) totalBytes / totalSeconds else AppConfig.ESTIMATED_VIDEO_BYTES_PER_SEC
    }

    fun estimatedRecordingSeconds(): Double? {
        val available = availableBytes() ?: return null
        if (available <= 0) return null
        return available.toDouble() / bytesPerSecondEstimate()
    }

    suspend fun refresh() {
        refreshMutex.withLock {
            try {
                val token = authManager.validAccessToken()
                quota.value = GoogleDriveService.getStorageQuota(token)
                forcedOutOfStorage.value = false
                lastRefreshedMs.value = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch Drive storage quota: ${e.message}")
            }
        }
    }

    suspend fun refreshIfStale() {
        val last = lastRefreshedMs.value
        if (last != null && System.currentTimeMillis() - last < QUOTA_REFRESH_INTERVAL_MS) {
            return
        }
        refresh()
    }

    fun markOutOfStorage() {
        forcedOutOfStorage.value = true
    }

    fun formatAvailableBytes(): String? = availableBytes()?.let { Formatters.formatBytes(context, it) }

    private companion object {
        const val TAG = "DriveStorageManager"
    }
}
