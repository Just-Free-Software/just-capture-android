package com.justapp.record.services

import android.content.Context
import android.util.Log
import com.justapp.record.data.SessionStore
import com.justapp.record.model.EmailJob
import com.justapp.record.model.EmailStatus
import com.justapp.record.util.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Persistent outbox for emergency-alert emails, mirroring the iOS app's
 * EmailQueueManager. Enqueued jobs are stored to disk (email_jobs.json) so
 * they survive a kill/relaunch mid-outage; on each launch unsent jobs are
 * resumed. Each send is retried with exponential backoff up to
 * RetryPolicy.MAX_ATTEMPTS; after that the job is left FAILED for a manual
 * retry from the dashboard.
 *
 * All list mutations happen on the main dispatcher (scope is Main.immediate);
 * file writes are deferred to a single-threaded IO context.
 */
class EmailQueueManager(
    context: Context,
    private val sessionStore: SessionStore,
    private val authManager: GoogleAuthManager,
    private val scope: CoroutineScope
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storageFile = File(context.filesDir, "email_jobs.json")
    private val storeContext = Dispatchers.IO.limitedParallelism(1)

    private val _jobs = MutableStateFlow<List<EmailJob>>(emptyList())
    val jobs: StateFlow<List<EmailJob>> = _jobs.asStateFlow()

    private val activeTasks = mutableMapOf<String, Job>()

    init {
        // One small JSON file at app start; blocking load keeps launch
        // ordering simple. Any job that was mid-send when the process died is
        // left SENDING on disk; reset those so resumeAll picks them up cleanly.
        runBlocking {
            withContext(storeContext) {
                if (storageFile.exists()) {
                    try {
                        _jobs.value = json.decodeFromString<List<EmailJob>>(storageFile.readText())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load email jobs", e)
                    }
                }
                val hadSending = _jobs.value.any { it.status == EmailStatus.SENDING }
                if (hadSending) {
                    _jobs.value = _jobs.value.map {
                        if (it.status == EmailStatus.SENDING) {
                            it.copy(status = EmailStatus.PENDING)
                        } else {
                            it
                        }
                    }
                    persist()
                }
            }
        }
    }

    fun enqueue(
        sessionId: String,
        recipients: List<String>,
        subject: String,
        bodyNote: String,
        includeTime: Boolean,
        address: String?,
        latitude: Double?,
        longitude: Double?
    ): EmailJob {
        val job = EmailJob(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            createdAtEpochMs = System.currentTimeMillis(),
            recipients = recipients,
            subject = subject,
            bodyNote = bodyNote,
            includeTime = includeTime,
            address = address,
            latitude = latitude,
            longitude = longitude
        )
        _jobs.value = listOf(job) + _jobs.value
        persistAsync()
        startSending(job.id)
        return job
    }

    /** Re-attempt every job that isn't yet confirmed sent. Called at app start. */
    fun resumeAll() {
        for (job in _jobs.value) {
            if (job.status != EmailStatus.SENT) {
                startSending(job.id)
            }
        }
    }

    /** Manual retry from the dashboard UI. */
    fun retry(jobId: String) {
        startSending(jobId)
    }

    /**
     * Drops every job tied to a session (e.g. when the session is deleted).
     * In-flight sends are cancelled first so they don't outlive the session.
     */
    fun removeJobs(sessionId: String) {
        val toRemove = _jobs.value.filter { it.sessionId == sessionId }
        for (job in toRemove) {
            activeTasks.remove(job.id)?.cancel()
        }
        _jobs.value = _jobs.value.filterNot { it.sessionId == sessionId }
        persistAsync()
    }

    private fun startSending(jobId: String) {
        activeTasks.remove(jobId)?.cancel()
        activeTasks[jobId] = scope.launch {
            var attempt = 0
            while (true) {
                val job = _jobs.value.firstOrNull { it.id == jobId } ?: return@launch
                if (job.status == EmailStatus.SENT) return@launch

                attempt += 1
                val currentAttempt = attempt
                updateJob(jobId) {
                    it.copy(
                        attempts = currentAttempt,
                        lastAttemptEpochMs = System.currentTimeMillis(),
                        status = EmailStatus.SENDING
                    )
                }

                try {
                    val token = authManager.validAccessToken()
                    // Resolve the Drive folder link at send time so the email
                    // picks up the link once the folder is eventually created,
                    // even if it wasn't ready when the job was first queued.
                    val driveLink = sessionStore.sessions.value
                        .firstOrNull { it.id == job.sessionId }
                        ?.driveFolderLink
                    GmailService.sendEmergencyEmail(
                        to = job.recipients,
                        subject = job.subject,
                        bodyNote = job.bodyNote,
                        includeTime = job.includeTime,
                        address = job.address,
                        latitude = job.latitude,
                        longitude = job.longitude,
                        driveFolderLink = driveLink,
                        accessToken = token
                    )
                    updateJob(jobId) { it.copy(status = EmailStatus.SENT) }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (currentAttempt >= RetryPolicy.MAX_ATTEMPTS) {
                        updateJob(jobId) { it.copy(status = EmailStatus.FAILED) }
                        Log.e(
                            TAG,
                            "Email to ${job.recipients.joinToString(", ")} failed after " +
                                "$currentAttempt attempts: ${e.message}"
                        )
                        return@launch
                    }
                    updateJob(jobId) { it.copy(status = EmailStatus.PENDING) }
                    val delaySec = RetryPolicy.delayForAttempt(currentAttempt)
                    Log.w(
                        TAG,
                        "Email attempt $currentAttempt failed, retrying in ${delaySec.toInt()}s: ${e.message}"
                    )
                    delay((delaySec * 1000).toLong())
                }
            }
        }
    }

    /** Mutates the job list on the main thread, then persists write-behind. */
    private fun updateJob(jobId: String, transform: (EmailJob) -> EmailJob) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        _jobs.value = _jobs.value.map { if (it.id == jobId) transform(it) else it }
        persistAsync()
    }

    private fun persistAsync() {
        scope.launch {
            withContext(storeContext) { persist() }
        }
    }

    private fun persist() {
        try {
            storageFile.writeText(json.encodeToString(_jobs.value))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save email jobs", e)
        }
    }

    private companion object {
        const val TAG = "EmailQueueManager"
    }
}
