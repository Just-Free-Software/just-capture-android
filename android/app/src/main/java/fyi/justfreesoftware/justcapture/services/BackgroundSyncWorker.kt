package fyi.justfreesoftware.justcapture.services

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fyi.justfreesoftware.justcapture.AppGraph
import fyi.justfreesoftware.justcapture.JustCaptureApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Keeps clips and emails moving after the app is left. Android may stop the
 * process at any time once it's off screen; this asks to be run again when
 * there's a connection, and holds the process while uploads are in flight.
 *
 * It does no uploading of its own: starting the process builds the app's
 * services, and their queues do the work. A run that ends with clips or
 * emails unfinished is repeated, with growing gaps while nothing gets through.
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // The queues are only touched on the main thread.
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val graph = (applicationContext as JustCaptureApplication).graph
        graph.awaitStarted()
        // A recording that just stopped is still writing its last clip.
        delay(SETTLE_MS)
        graph.sweep()
        delay(SETTLE_MS)

        val deadline = System.currentTimeMillis() + RUN_LIMIT_MS
        // Offline, the work is only waiting, so there's no reason to stay awake.
        while (isBusy(graph) && graph.networkMonitor.isOnline && System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
        }
        if (graph.hasOutstandingWork) Result.retry() else Result.success()
    }

    private fun isBusy(graph: AppGraph) = graph.hasActiveWork || graph.cameraManager.isFinishing

    companion object {
        private const val WORK_NAME = "background-sync"
        private const val SETTLE_MS = 2_000L
        private const val POLL_MS = 500L

        /** Android stops a worker after ten minutes; finish before that and ask to run again. */
        private const val RUN_LIMIT_MS = 8 * 60_000L
        private const val RETRY_BACKOFF_MINUTES = 5L

        /** Does nothing if a run is already queued or in progress. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackgroundSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
