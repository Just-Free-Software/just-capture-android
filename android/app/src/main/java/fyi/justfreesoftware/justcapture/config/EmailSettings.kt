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
        /** Stands in for the sender in the subject; filled in when the email is sent. */
        const val NAME_TOKEN = "[your_name]"
        const val DEFAULT_SUBJECT = "$NAME_TOKEN started a recording"

        /**
         * Defaults from earlier builds. Settings used to save the default into
         * storage, so these are treated as "not customised" and replaced.
         */
        private val LEGACY_SUBJECTS = setOf(
            "🚨 Emergency Recording Alert",
            "Alert! [your_name] is broadcasting",
            "[your_name] started recording",
            "I have started a recording"
        )

        fun isDefaultSubject(subject: String): Boolean = subject.isEmpty() || subject in LEGACY_SUBJECTS

        /** Note for the second email, sent if Drive fills up mid-recording. */
        const val STORAGE_FULL_NOTE =
            "Google Drive storage is full, so new clips cannot be uploaded right now. " +
                "Recording is still in progress and the app will keep retrying — it will try to " +
                "upload the remaining clips once storage is freed."

        private val SEPARATORS = Regex("[,;\\s]+")
        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        /**
         * [NAME_TOKEN] becomes the Google profile name, or the signed-in email
         * if the user didn't grant profile access or signed in before it was
         * asked for.
         */
        fun from(settings: Settings, accountName: String?, accountEmail: String?): EmailSettings {
            val rawSubject = settings.emailSubject.trim()
            val subject = if (isDefaultSubject(rawSubject)) DEFAULT_SUBJECT else rawSubject
            val sender = listOf(accountName, accountEmail).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
                ?: "Someone"
            return EmailSettings(
                alertsEnabled = settings.emailAlertsEnabled,
                recipients = parseRecipients(settings.emailRecipients),
                subject = subject.replace(NAME_TOKEN, sender),
                note = settings.emailNote.trim(),
                includeLocation = settings.includeLocationInEmail
            )
        }

        /** Splits the comma/semicolon/whitespace-separated recipients field. */
        fun parseRecipients(raw: String): List<String> =
            raw.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

        fun isValidEmail(address: String): Boolean = EMAIL.matches(address)
    }
}
