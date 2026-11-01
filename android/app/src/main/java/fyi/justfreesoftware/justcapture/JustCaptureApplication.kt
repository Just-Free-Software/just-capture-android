package fyi.justfreesoftware.justcapture

import android.app.Application
import android.util.Log
import fyi.justfreesoftware.justcapture.data.SessionStore
import fyi.justfreesoftware.justcapture.data.Settings
import fyi.justfreesoftware.justcapture.services.CameraManager
import fyi.justfreesoftware.justcapture.services.DriveStorageManager
import fyi.justfreesoftware.justcapture.services.EmailQueueManager
import fyi.justfreesoftware.justcapture.services.GoogleAuthManager
import fyi.justfreesoftware.justcapture.services.LocationManager
import fyi.justfreesoftware.justcapture.services.UploadQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Dependency graph for the app, mirroring the iOS app's environment objects.
 * Built once in [onCreate]; survives for the process lifetime so backgrounded
 * uploads/email retries keep running while the process is alive.
 */
class AppGraph(private val appContext: android.content.Context, val appScope: CoroutineScope) {
    val settings = Settings(appContext)
    val sessionStore = SessionStore(appContext)
    val authManager = GoogleAuthManager(appContext, appScope)
    val cameraManager = CameraManager(appContext, appScope)
    val locationManager = LocationManager(appContext, appScope)
    val driveStorageManager = DriveStorageManager(appContext, sessionStore, authManager)
    val emailQueueManager = EmailQueueManager(appContext, sessionStore, authManager, appScope)
    val uploadQueueManager =
        UploadQueueManager(sessionStore, settings, authManager, appScope)
}

class JustCaptureApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this, appScope)

        // After sign-in is restored, drain anything left over from a previous
        // launch: pending chunk uploads and unsent alert emails.
        appScope.launch {
            graph.authManager.restoreSignIn()
            graph.uploadQueueManager.resumePendingUploads()
            graph.emailQueueManager.resumeAll()
            Log.i(TAG, "Launch recovery complete")
        }
    }

    private companion object {
        const val TAG = "JustCaptureApplication"
    }
}
