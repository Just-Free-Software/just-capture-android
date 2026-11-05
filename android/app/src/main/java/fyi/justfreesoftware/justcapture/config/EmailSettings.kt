package fyi.justfreesoftware.justcapture.config

import fyi.justfreesoftware.justcapture.data.Settings

/**
 * Snapshot of the notification-email preferences the Settings screen writes.
 */
data class EmailSettings(
    val alertsEnabled: Boolean,
    val recipients: List<String>,
    val subject: String,
    val note: String,
    val includeTime: Boolean,
    /**
     * The app-wide location switch, not just an email option: when off, the
     * app doesn't fetch location for recordings or emails. The storage key
     * keeps its older email-specific name so existing choices carry over.
     */
    val includeLocation: Boolean
) {
    /** Whether an alert would actually go out with these settings. */
    val canSend: Boolean
        get() = alertsEnabled && recipients.isNotEmpty()

    companion object {
        const val DEFAULT_SUBJECT = "I have started a recording"

        /**
         * Defaults from earlier builds. Settings used to save the default into
         * storage, so these are treated as "not customised" and replaced.
         */
        private val LEGACY_SUBJECTS = setOf(
            "🚨 Emergency Recording Alert",
            "Alert! [your_name] is broadcasting",
            "[your_name] started recording"
        )

        fun isDefaultSubject(subject: String): Boolean = subject.isEmpty() || subject in LEGACY_SUBJECTS

        /** Note for the second email, sent if Drive fills up mid-recording. */
        const val STORAGE_FULL_NOTE =
            "Google Drive storage is full, so new clips cannot be uploaded right now. " +
                "Recording is still in progress and the app will keep retrying — it will try to " +
                "upload the remaining clips once storage is freed."

        private val SEPARATORS = Regex("[,;\\s]+")
        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        fun from(settings: Settings): EmailSettings {
            val rawSubject = settings.emailSubject.trim()
            return EmailSettings(
                alertsEnabled = settings.emailAlertsEnabled,
                recipients = parseRecipients(settings.emailRecipients),
                subject = if (isDefaultSubject(rawSubject)) DEFAULT_SUBJECT else rawSubject,
                note = settings.emailNote.trim(),
                includeTime = settings.includeTimeInEmail,
                includeLocation = settings.includeLocationInEmail
            )
        }

        /** Splits the comma/semicolon/whitespace-separated recipients field. */
        fun parseRecipients(raw: String): List<String> =
            raw.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

        fun isValidEmail(address: String): Boolean = EMAIL.matches(address)
    }
}
