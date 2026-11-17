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

        /** Subject for the low- and full-storage emails, naming whose Drive needs clearing. */
        fun storageSubject(email: String?): String {
            val owner = email?.trim().orEmpty()
            val drive = if (owner.isEmpty()) "the Google Drive" else "$owner's Google Drive"
            return "Action Needed: Free up space on $drive for Just Capture"
        }

        /** Note for the second email, sent when Drive is close to full mid-recording. */
        const val STORAGE_LOW_NOTE =
            "Google Drive storage is almost full. Recording is still in progress, but once storage runs out, " +
                "new clips can't be uploaded until space is freed. Once it is, the clips will upload on their " +
                "own if the phone is on and Just Capture hasn't been force-closed, though this can take a " +
                "while. You may not get another email from this account after that. If you have access to " +
                "this Google account or manage its family plan, you can add storage at one.google.com."

        /** Used instead if Drive fills up before the low-storage email went out. */
        const val STORAGE_FULL_NOTE =
            "Google Drive storage is full, so new clips can't be uploaded right now. Once space is freed, " +
                "the clips will upload on their own if the phone is on and Just Capture hasn't been " +
                "force-closed, though this can take a while. If you have access to this Google account or " +
                "manage its family plan, you can add storage at one.google.com."

        private val SEPARATORS = Regex("[,;\\s]+")
        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        fun from(settings: Settings): EmailSettings {
            val rawSubject = settings.emailSubject.trim()
            return EmailSettings(
                alertsEnabled = settings.emailAlertsEnabled,
                recipients = parseRecipients(settings.emailRecipients),
                subject = if (isDefaultSubject(rawSubject)) DEFAULT_SUBJECT else rawSubject,
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
