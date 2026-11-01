package fyi.justfreesoftware.justcapture.config

import fyi.justfreesoftware.justcapture.data.Settings

/**
 * Snapshot of the alert-email preferences the Settings screen writes.
 */
data class EmailSettings(
    val alertsEnabled: Boolean,
    val recipients: List<String>,
    val subject: String,
    val note: String,
    val includeTime: Boolean,
    val includeLocation: Boolean
) {
    /** Whether an alert would actually go out with these settings. */
    val canSend: Boolean
        get() = alertsEnabled && recipients.isNotEmpty()

    companion object {
        const val DEFAULT_SUBJECT = "Alert! [your_name] is broadcasting"
        const val NAME_TOKEN = "[your_name]"

        /** Subject used by early builds; treated as "not customised". */
        const val LEGACY_SUBJECT = "🚨 Emergency Recording Alert"

        /** Note for the second email, sent if Drive fills up mid-recording. */
        const val STORAGE_FULL_NOTE =
            "Google Drive storage is full, so new clips cannot be uploaded right now. " +
                "Recording is still in progress and the app will keep retrying — clips will " +
                "upload automatically as soon as storage is freed."

        private val SEPARATORS = Regex("[,;\\s]+")
        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        fun from(settings: Settings): EmailSettings {
            val rawSubject = settings.emailSubject.trim()
            return EmailSettings(
                alertsEnabled = settings.emailAlertsEnabled,
                recipients = parseRecipients(settings.emailRecipients),
                subject = if (rawSubject.isEmpty() || rawSubject == LEGACY_SUBJECT) DEFAULT_SUBJECT else rawSubject,
                note = settings.emailNote.trim(),
                includeTime = settings.includeTimeInEmail,
                includeLocation = settings.includeLocationInEmail
            )
        }

        /** Splits the comma/semicolon/whitespace-separated recipients field. */
        fun parseRecipients(raw: String): List<String> =
            raw.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

        fun isValidEmail(address: String): Boolean = EMAIL.matches(address)

        /**
         * Replaces the `[your_name]` placeholder so recipients see who is
         * actually broadcasting instead of the literal token.
         */
        fun personalize(text: String, name: String?, email: String?): String {
            val display = listOf(name, email)
                .mapNotNull { it?.trim() }
                .firstOrNull { it.isNotEmpty() } ?: "Someone"
            return text.replace(NAME_TOKEN, display)
        }
    }
}
