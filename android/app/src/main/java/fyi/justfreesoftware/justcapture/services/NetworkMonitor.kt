package fyi.justfreesoftware.justcapture.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells the retry loops whether the phone has a connection, so being offline
 * doesn't use up a clip's or email's retries, and so they try again as soon as
 * the connection returns instead of sitting out a long backoff.
 *
 * State is only touched from [scope], which runs on the main thread.
 */
class NetworkMonitor(context: Context, private val scope: CoroutineScope) {
    /** Assumed online if the system can't be asked, so a wrong reading can only cost a retry. */
    var isOnline = true
        private set

    /** Called when a returning connection has settled. */
    var onRestored: (() -> Unit)? = null

    private var settleJob: Job? = null
    private val restored = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            // With no network at all, no callback arrives to say so.
            isOnline = manager.activeNetwork != null
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { update(online = true) }
                }

                override fun onLost(network: Network) {
                    scope.launch { update(online = false) }
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't watch the network: ${e.message}")
        }
    }

    private fun update(online: Boolean) {
        if (online == isOnline) return
        isOnline = online
        settleJob?.cancel()
        settleJob = null
        if (!online) return
        settleJob = scope.launch {
            delay(SETTLE_DELAY_MS)
            if (!isOnline) return@launch
            restored.tryEmit(Unit)
            onRestored?.invoke()
        }
    }

    /**
     * Whether a failure came from having no connection. These don't use up
     * retries: nothing reached Google, and the retry happens when it returns.
     */
    fun isOffline(error: Throwable): Boolean = !isOnline || error is UnknownHostException

    /**
     * Returns after [timeoutMs] or when a lost connection comes back,
     * whichever is first. The timeout means a wrong reading of the network
     * can only delay a retry, not stop it.
     */
    suspend fun waitForRestoredConnection(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { restored.first() }
    }

    private companion object {
        const val TAG = "NetworkMonitor"

        /**
         * A connection that returns has to hold for this long before anything
         * retries, so a flapping connection can't turn into a burst of requests.
         */
        const val SETTLE_DELAY_MS = 2_000L
    }
}
