package fyi.justfreesoftware.justcapture.services

import android.content.Context
import android.util.Log
import fyi.justfreesoftware.justcapture.data.SessionStore
import fyi.justfreesoftware.justcapture.model.EmailJob
import fyi.justfreesoftware.justcapture.model.EmailStatus
import fyi.justfreesoftware.justcapture.util.RetryPolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent outbox for alert emails. Jobs are stored to disk
 * (email_jobs.json) so they survive a kill/relaunch mid-outage; unsent jobs
 * are resumed after sign-in. Each send is retried with exponential backoff up
 * to RetryPolicy.MAX_ATTEMPTS tries while online; after that the job is left
 * FAILED until the app next retries failed work or the user retries it from
 * My Clips.
 *
 * The job list is only mutated on the main thread ([scope] is
 * Main.immediate); file writes go to a single-threaded IO context.
 */
class EmailQueueManager(
    context: Context,
    private val sessionStore: SessionStore,
    private val authManager: GoogleAuthManager,
    private val locationManager: LocationManager,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "EmailQueueManager"

        /**
         * How long the first send waits for a GPS fix when the job wants a
         * location but none was known yet. Short, because getting the alert
         * out matters more than the map link.
         */
        const val LOCATION_WAIT_MS = 6_000L

        /**
         * How long the first send waits for the session's Drive folder,
         * which is created in parallel when recording starts. Without this
         * the email usually wins the race and goes out with no link at all.
         */
        const val FOLDER_WAIT_MS = 10_000L

        private const val POLL_MS = 250L
    }

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
        // One small JSON file at app start. Any job that was mid-send when the
        // process died is left SENDING on disk; reset those so resumeAll
        // picks them up cleanly.
        if (storageFile.exists()) {
            try {
                _jobs.value = json.decodeFromString<List<EmailJob>>(storageFile.readText())
                    .map { if (it.status == EmailStatus.SENDING) it.copy(status = EmailStatus.PENDING) else it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load email jobs", e)
                // The next save would replace a file this build can't read
                // with the jobs it knows about, which is none. Keep a copy so
                // the outbox isn't destroyed by a decoding mistake.
                runCatching {
                    storageFile.copyTo(File(context.filesDir, "email_jobs.unreadable.json"), overwrite = true)
                }
            }
        }
    }

    /** Sends are running or waiting to retry. */
    val hasActiveWork: Boolean
        get() = activeTasks.values.any { it.isActive }

    /** Something still needs the app to run: an unsent email, or a link owed to someone who already got the alert. */
    val hasOutstandingWork: Boolean
        get() = _jobs.value.any { it.status != EmailStatus.SENT || it.needsLinkFollowUp }

    private fun job(id: String): EmailJob? = _jobs.value.firstOrNull { it.id == id }

    fun enqueue(
        sessionId: String,
        recipients: List<String>,
        subject: String,
        bodyNote: String,
        includeLocation: Boolean
    ): EmailJob {
        val fix = if (includeLocation) locationManager.freshFix() else null
        val job = EmailJob(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            createdAtEpochMs = System.currentTimeMillis(),
            recipients = recipients,
            subject = subject,
            bodyNote = bodyNote,
            includeLocation = includeLocation,
            address = fix?.let { locationManager.address(it) },
            latitude = fix?.latitude,
            longitude = fix?.longitude
        )
        _jobs.value = listOf(job) + _jobs.value
        save()
        startSending(job.id)
        return job
    }

    /**
     * Restarts every unsent job that isn't already running. [retryFailed]
     * also gives jobs that ran out of tries a fresh set.
     */
    fun resumeAll(retryFailed: Boolean) {
        for (job in _jobs.value) {
            val isDone = job.status == EmailStatus.SENT || activeTasks[job.id]?.isActive == true
            val isOutOfTries = job.status == EmailStatus.FAILED && !retryFailed
            if (!isDone && !isOutOfTries) startSending(job.id)
        }
        // Covers a follow-up that was owed when the app was killed.
        _jobs.value.filter { it.needsLinkFollowUp }.map { it.sessionId }.toSet().forEach { sendLinkFollowUps(it) }
    }

    /**
     * Sends the recording link to anyone whose alert went out before the
     * session's Drive folder existed. Called when the folder is created, and
     * does nothing until then.
     */
    fun sendLinkFollowUps(sessionId: String) {
        if (sessionStore.session(sessionId)?.driveFolderId == null) return
        val owed = _jobs.value.filter {
            it.sessionId == sessionId && it.status == EmailStatus.SENT && it.needsLinkFollowUp
        }
        if (owed.isEmpty()) return
        val followUps = owed.map { original ->
            EmailJob(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                createdAtEpochMs = original.createdAtEpochMs,
                recipients = original.recipients,
                subject = "Recording link: ${original.subject}",
                bodyNote = "The link to the recording wasn't ready when the first email went out. Here it is.",
                isLinkFollowUp = true
            )
        }
        val owedIds = owed.map { it.id }.toSet()
        _jobs.value = followUps + _jobs.value.map {
            if (it.id in owedIds) it.copy(needsLinkFollowUp = false) else it
        }
        save()
        followUps.forEach { startSending(it.id) }
    }

    /** Manual retry from My Clips. */
    fun retry(jobId: String) = startSending(jobId)

    /**
     * Drops every job tied to a session (e.g. when the session is deleted).
     * In-flight sends are cancelled first so they don't outlive the session.
     */
    fun removeJobs(sessionId: String) {
        _jobs.value.filter { it.sessionId == sessionId }.forEach { activeTasks.remove(it.id)?.cancel() }
        _jobs.value = _jobs.value.filterNot { it.sessionId == sessionId }
        save()
    }

    /** Fills in the job's location from a fresh fix, waiting briefly for GPS on the first attempt. */
    private suspend fun resolveLocation(jobId: String, waitMs: Long) {
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            val job = job(jobId) ?: return
            if (!job.includeLocation) return
            val fix = locationManager.freshFix()
            if (fix != null && (job.latitude == null || fix.timeMs > job.createdAtEpochMs)) {
                // Keep the address paired with the coordinates it came from.
                updateJob(jobId, persist = false) {
                    it.copy(latitude = fix.latitude, longitude = fix.longitude, address = locationManager.address(fix))
                }
            }
            val updated = job(jobId) ?: return
            if (updated.latitude != null && updated.address != null) return
            if (System.currentTimeMillis() >= deadline || !scope.isActive) return
            delay(POLL_MS)
        }
    }

    /** Gives a job's first try a moment for the Drive folder and a location to turn up. */
    private suspend fun waitForDetails(job: EmailJob, isFirstTry: Boolean) {
        if (!isFirstTry) {
            resolveLocation(job.id, waitMs = 0L)
            return
        }
        // The folder is created in parallel when recording starts. No session
        // (e.g. deleted) means there's no folder to wait for.
        val folderDeadline = System.currentTimeMillis() + FOLDER_WAIT_MS
        while (sessionStore.session(job.sessionId)?.driveFolderId == null &&
            sessionStore.session(job.sessionId) != null && System.currentTimeMillis() < folderDeadline
        ) {
            delay(POLL_MS)
        }
        // The location wait counts from when the job was queued, so it
        // overlaps the folder wait instead of adding to it.
        val elapsed = System.currentTimeMillis() - job.createdAtEpochMs
        resolveLocation(job.id, waitMs = maxOf(0L, LOCATION_WAIT_MS - elapsed))
    }

    private fun startSending(jobId: String) {
        activeTasks.remove(jobId)?.cancel()
        activeTasks[jobId] = scope.launch {
            // Tries left are counted per run, unlike the job's `attempts`, so a
            // job restarted later isn't down to its last try. Offline tries
            // don't count.
            var failures = 0
            while (true) {
                val queued = job(jobId) ?: return@launch
                if (queued.status == EmailStatus.SENT) {
                    activeTasks.remove(jobId)
                    return@launch
                }

                val currentAttempt = queued.attempts + 1
                updateJob(jobId) {
                    it.copy(
                        attempts = currentAttempt,
                        lastAttemptEpochMs = System.currentTimeMillis(),
                        status = EmailStatus.SENDING
                    )
                }

                waitForDetails(queued, isFirstTry = currentAttempt == 1)

                val job = job(jobId) ?: return@launch
                try {
                    val token = authManager.validAccessToken()
                    // Resolve the Drive folder link at send time so the email
                    // picks it up once the folder exists, even if it wasn't
                    // ready when the job was queued.
                    val driveLink = sessionStore.session(job.sessionId)?.driveFolderLink
                    GmailService.sendEmergencyEmail(
                        to = job.recipients,
                        subject = job.subject,
                        bodyNote = job.bodyNote,
                        startedAtMs = job.createdAtEpochMs,
                        address = job.address,
                        latitude = job.latitude,
                        longitude = job.longitude,
                        driveFolderLink = driveLink,
                        accessToken = token
                    )
                    val owesLink = driveLink == null && !job.isLinkFollowUp
                    updateJob(jobId) { it.copy(status = EmailStatus.SENT, needsLinkFollowUp = owesLink) }
                    activeTasks.remove(jobId)
                    // The folder may have appeared while this was sending.
                    if (owesLink) sendLinkFollowUps(job.sessionId)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (network.isOffline(e)) {
                        updateJob(jobId) { it.copy(status = EmailStatus.PENDING) }
                        network.waitForRestoredConnection(RetryPolicy.MAX_DELAY_MS)
                        continue
                    }
                    failures += 1
                    if (failures >= RetryPolicy.MAX_ATTEMPTS) {
                        updateJob(jobId) { it.copy(status = EmailStatus.FAILED) }
                        Log.e(TAG, "Email for session ${job.sessionId} failed after $failures attempts: ${e.message}")
                        activeTasks.remove(jobId)
                        return@launch
                    }
                    updateJob(jobId) { it.copy(status = EmailStatus.PENDING) }
                    val delaySec = RetryPolicy.delayForAttempt(failures)
                    Log.w(TAG, "Email attempt $failures failed, retrying in ${delaySec.toInt()}s: ${e.message}")
                    network.waitForRestoredConnection((delaySec * 1000).toLong())
                }
            }
        }
    }

    private fun updateJob(jobId: String, persist: Boolean = true, transform: (EmailJob) -> EmailJob) {
        _jobs.value = _jobs.value.map { if (it.id == jobId) transform(it) else it }
        if (persist) save()
    }

    /** Write-behind: snapshots the list now, writes it off the main thread. */
    private fun save() {
        val snapshot = _jobs.value
        scope.launch(storeContext) {
            try {
                val tmp = File(storageFile.path + ".tmp")
                tmp.writeText(json.encodeToString(snapshot))
                tmp.renameTo(storageFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save email jobs", e)
            }
        }
    }
}
