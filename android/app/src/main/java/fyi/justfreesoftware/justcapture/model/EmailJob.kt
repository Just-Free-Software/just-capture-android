package fyi.justfreesoftware.justcapture.model

import kotlinx.serialization.Serializable

@Serializable
enum class EmailStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED
}

@Serializable
data class EmailJob(
    val id: String,
    val sessionId: String,
    val createdAtEpochMs: Long,
    val recipients: List<String>,
    val subject: String,
    val bodyNote: String,
    val includeTime: Boolean,
    /**
     * When true and no coordinates were known at enqueue time, the queue
     * fills them in from a fresh fix right before sending.
     */
    val includeLocation: Boolean = false,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val status: EmailStatus = EmailStatus.PENDING,
    val attempts: Int = 0,
    val lastAttemptEpochMs: Long? = null,
    /**
     * Set when this alert went out before the session's Drive folder existed,
     * so it carried no link. Cleared once a link follow-up has been queued.
     */
    val needsLinkFollowUp: Boolean = false,
    /**
     * True for the follow-up email that delivers a late link. Follow-ups
     * never owe a follow-up of their own.
     */
    val isLinkFollowUp: Boolean = false
)
