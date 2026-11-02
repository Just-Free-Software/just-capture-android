package fyi.justfreesoftware.justcapture.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import fyi.justfreesoftware.justcapture.config.AppConfig
import kotlin.reflect.KProperty

/**
 * SharedPreferences-backed settings. Each value is mirrored in Compose state,
 * so every screen that reads one recomposes when another screen changes it
 * (like the iOS app's @AppStorage). Keys and defaults match the iOS app.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("just_capture_settings", Context.MODE_PRIVATE)

    var chunkDurationSec by doublePref("chunkDuration", AppConfig.DEFAULT_CHUNK_DURATION_SEC)
    var emailRecipients by stringPref("emailRecipients", "")
    var emailSubject by stringPref("emailSubject", "")
    var emailNote by stringPref("emailNote", "")
    var emailAlertsEnabled by boolPref("emailAlertsEnabled", true)
    var includeTimeInEmail by boolPref("includeTimeInEmail", true)
    var includeLocationInEmail by boolPref("includeLocationInEmail", true)
    var hideSendClipTip by boolPref("hideSendClipTip", false)

    private inner class Pref<T>(
        private val state: MutableState<T>,
        private val write: (SharedPreferences.Editor, T) -> Unit
    ) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            prefs.edit().also { write(it, value) }.apply()
        }
    }

    private fun stringPref(key: String, default: String) =
        Pref(mutableStateOf(prefs.getString(key, default) ?: default)) { e, v -> e.putString(key, v) }

    private fun boolPref(key: String, default: Boolean) =
        Pref(mutableStateOf(prefs.getBoolean(key, default))) { e, v -> e.putBoolean(key, v) }

    private fun doublePref(key: String, default: Double) =
        Pref(mutableStateOf(prefs.getFloat(key, default.toFloat()).toDouble())) { e, v ->
            e.putFloat(key, v.toFloat())
        }
}
