package fyi.justfreesoftware.justcapture.util

import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

object Formatters {

    /** `m:ss`, or `h:mm:ss` past an hour. */
    fun duration(seconds: Double): String {
        val total = seconds.roundToLong().coerceAtLeast(0)
        val hours = total / 3600
        val minutes = total / 60 % 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, secs)
        }
    }

    /** Decimal units, for file sizes. */
    fun bytes(bytes: Long): String = scaled(bytes, 1000.0)

    /**
     * Binary units, as Google uses: a free account's "15 GB" is 15 GiB, which
     * decimal formatting would show as 16.1 GB.
     */
    fun storageBytes(bytes: Long): String = scaled(bytes, 1024.0)

    private fun scaled(bytes: Long, unit: Double): String {
        if (bytes < unit) return "$bytes bytes"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / unit
        var index = 0
        while (value >= unit && index < units.lastIndex) {
            value /= unit
            index++
        }
        val pattern = if (value >= 100 || index == 0) "%.0f %s" else "%.1f %s"
        return String.format(Locale.getDefault(), pattern, value, units[index])
    }

    fun recordingTime(seconds: Double): String {
        val total = seconds.toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        if (hours > 0) return "$hours hr $minutes min"
        if (minutes > 0) return "$minutes min"
        return "less than a minute"
    }

    /** e.g. "Oct 14, 3:05 PM". */
    fun sessionDate(epochMs: Long): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMs))

    /** e.g. "Oct 14, 2026, 3:05 PM". */
    fun sessionDateLong(epochMs: Long): String =
        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(Date(epochMs))

    fun time(epochMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epochMs))

    fun emailTimestamp(epochMs: Long): String =
        SimpleDateFormat("MMM d, yyyy 'at' h:mm a zzz", Locale.US).format(Date(epochMs))

    fun relative(epochMs: Long): String =
        DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}
