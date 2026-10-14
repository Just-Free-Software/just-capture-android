package com.justapp.record.data

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed settings, mirroring the iOS app's UserDefaults keys.
 * Defaults match the iOS defaults.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("just_settings", Context.MODE_PRIVATE)

    var chunkDurationSec: Double
        get() = prefs.getFloat(KEY_CHUNK_DURATION, 30.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_CHUNK_DURATION, value.toFloat()).apply()

    var emailRecipients: String
        get() = prefs.getString(KEY_EMAIL_RECIPIENTS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMAIL_RECIPIENTS, value).apply()

    var emailSubject: String
        get() = prefs.getString(KEY_EMAIL_SUBJECT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMAIL_SUBJECT, value).apply()

    var emailNote: String
        get() = prefs.getString(KEY_EMAIL_NOTE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMAIL_NOTE, value).apply()

    var storageFullEmailNote: String
        get() = prefs.getString(KEY_STORAGE_FULL_EMAIL_NOTE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STORAGE_FULL_EMAIL_NOTE, value).apply()

    var emailAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMAIL_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_EMAIL_ALERTS_ENABLED, value).apply()

    var includeTimeInEmail: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_TIME, true)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_TIME, value).apply()

    var includeLocationInEmail: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_LOCATION, true)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_LOCATION, value).apply()

    var saveChunksLocally: Boolean
        get() = prefs.getBoolean(KEY_SAVE_CHUNKS_LOCALLY, true)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_CHUNKS_LOCALLY, value).apply()

    fun recipientsList(): List<String> =
        emailRecipients.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        const val KEY_CHUNK_DURATION = "chunkDuration"
        const val KEY_EMAIL_RECIPIENTS = "emailRecipients"
        const val KEY_EMAIL_SUBJECT = "emailSubject"
        const val KEY_EMAIL_NOTE = "emailNote"
        const val KEY_STORAGE_FULL_EMAIL_NOTE = "storageFullEmailNote"
        const val KEY_EMAIL_ALERTS_ENABLED = "emailAlertsEnabled"
        const val KEY_INCLUDE_TIME = "includeTimeInEmail"
        const val KEY_INCLUDE_LOCATION = "includeLocationInEmail"
        const val KEY_SAVE_CHUNKS_LOCALLY = "saveChunksLocally"
    }
}
