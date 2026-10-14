package com.justapp.record.util

import android.content.Context
import android.text.Formatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    fun formatBytes(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

    fun formatRecordingTime(seconds: Double): String {
        val total = seconds.toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        if (hours > 0) return "$hours hr $minutes min"
        if (minutes > 0) return "$minutes min"
        return "less than a minute"
    }

    fun formatElapsed(seconds: Double): String {
        val total = seconds.toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }

    fun formatSessionDate(epochMs: Long): String =
        SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(epochMs))

    fun formatEmailTimestamp(epochMs: Long): String =
        SimpleDateFormat("MMM d, yyyy 'at' h:mm a z", Locale.US).format(Date(epochMs))
}
