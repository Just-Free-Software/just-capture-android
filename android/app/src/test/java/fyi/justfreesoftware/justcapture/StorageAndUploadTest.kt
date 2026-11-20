package fyi.justfreesoftware.justcapture

import fyi.justfreesoftware.justcapture.config.EmailSettings
import fyi.justfreesoftware.justcapture.services.DriveStorageManager
import fyi.justfreesoftware.justcapture.services.DriveStorageQuota
import fyi.justfreesoftware.justcapture.services.GoogleDriveService
import fyi.justfreesoftware.justcapture.services.UploadQueueManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAndUploadTest {
    private val gib = 1024L * 1024 * 1024

    private fun shouldEmail(quota: DriveStorageQuota?, forcedOut: Boolean = false) =
        DriveStorageManager.shouldSendLowStorageEmail(quota, forcedOut)

    @Test
    fun lowStorageEmailOnlyWhenNearlyFull() {
        assertTrue(shouldEmail(DriveStorageQuota(limit = 15 * gib, usage = 15 * gib - 1)))
        assertTrue(shouldEmail(DriveStorageQuota(limit = 15 * gib, usage = 14 * gib + 1)))
        assertFalse(shouldEmail(DriveStorageQuota(limit = 15 * gib, usage = 14 * gib)))
        // Out of space is the storage-full email's job.
        assertFalse(shouldEmail(DriveStorageQuota(limit = 15 * gib, usage = 15 * gib)))
        assertFalse(shouldEmail(DriveStorageQuota(limit = 15 * gib, usage = 15 * gib - 1), forcedOut = true))
        // Unlimited, or not known yet.
        assertFalse(shouldEmail(DriveStorageQuota(limit = null, usage = 5 * gib)))
        assertFalse(shouldEmail(null))
    }

    @Test
    fun storageSubjectNamesTheAccount() {
        assertEquals(
            "Action Needed: Free up space on someone@example.com's Google Drive for Just Capture",
            EmailSettings.storageSubject(" someone@example.com ")
        )
        assertEquals(
            "Action Needed: Free up space on the Google Drive for Just Capture",
            EmailSettings.storageSubject(null)
        )
    }

    @Test
    fun resumesAfterTheLastByteDriveHas() {
        assertEquals(0L, GoogleDriveService.nextUploadOffset(null))
        assertEquals(2_097_152L, GoogleDriveService.nextUploadOffset("bytes=0-2097151"))
        assertEquals(0L, GoogleDriveService.nextUploadOffset("nonsense"))
    }

    @Test
    fun storageRecheckBacksOffToFifteenMinutes() {
        assertEquals(5 * 60.0, UploadQueueManager.storageRetryDelay(1), 0.0)
        assertEquals(10 * 60.0, UploadQueueManager.storageRetryDelay(2), 0.0)
        assertEquals(15 * 60.0, UploadQueueManager.storageRetryDelay(3), 0.0)
        assertEquals(15 * 60.0, UploadQueueManager.storageRetryDelay(20), 0.0)
    }
}
