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
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val status: EmailStatus = EmailStatus.PENDING,
    val attempts: Int = 0,
    val lastAttemptEpochMs: Long? = null
)
