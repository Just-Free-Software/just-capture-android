package fyi.justfreesoftware.justcapture.util

import android.content.Context
import android.os.StatFs

/**
 * Free space on this phone. Clips are always kept locally after upload, so
 * the phone filling up would stop new clips from being written.
 */
object DeviceStorage {
    /** Warn when less than this much recording time fits on the phone... */
    const val LOW_RECORDING_SECONDS_THRESHOLD = 30 * 60.0

    /** ...or when less than this many bytes are free, whichever is larger. */
    const val LOW_BYTES_THRESHOLD = 1024L * 1024 * 1024

    /** A local filesystem query, so it's cheap to call repeatedly. */
    fun availableBytes(context: Context): Long? =
        runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()

    fun isLow(availableBytes: Long, bytesPerSecond: Double): Boolean {
        val threshold = maxOf(LOW_BYTES_THRESHOLD, (LOW_RECORDING_SECONDS_THRESHOLD * bytesPerSecond).toLong())
        return availableBytes < threshold
    }

    fun recordingSeconds(availableBytes: Long, bytesPerSecond: Double): Double {
        if (bytesPerSecond <= 0) return 0.0
        return maxOf(availableBytes, 0L) / bytesPerSecond
    }
}
