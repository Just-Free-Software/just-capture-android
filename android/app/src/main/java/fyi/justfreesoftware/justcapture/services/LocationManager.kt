package fyi.justfreesoftware.justcapture.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import android.location.LocationManager as SystemLocationManager

/** A location fix and when it was taken (wall clock). */
data class Fix(val latitude: Double, val longitude: Double, val timeMs: Long)

/**
 * One-shot location + reverse geocoding. The current fix is exposed as
 * Compose state; the address is reverse geocoded (falling back to raw
 * coordinates) and kept paired with the fix it came from.
 */
class LocationManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    var currentFix by mutableStateOf<Fix?>(null)
        private set

    /** The geocoded address and the fix time it belongs to. */
    private var currentAddress: Pair<Long, String>? = null

    /** Refreshed by the UI after permission prompts and on resume. */
    var permissionGranted by mutableStateOf(hasLocationPermission())
        private set

    /** True once the user has answered the permission prompt at least once. */
    var permissionAsked by mutableStateOf(false)

    private val locationManager: SystemLocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? SystemLocationManager

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun refreshPermission() {
        val granted = hasLocationPermission()
        // Warm up a fix as soon as we're allowed to, so the first alert email
        // isn't sent before any location is known.
        if (granted && !permissionGranted) requestLocation()
        permissionGranted = granted
    }

    /**
     * Returns the current fix only if it was taken within [maxAgeMs]. An old
     * fix from a previous launch could be miles away, which is worse than no
     * location in an alert email.
     */
    fun freshFix(maxAgeMs: Long = 120_000): Fix? =
        currentFix?.takeIf { System.currentTimeMillis() - it.timeMs <= maxAgeMs }

    /** The geocoded address, only if it belongs to the given fix. */
    fun address(fix: Fix): String? = currentAddress?.takeIf { it.first == fix.timeMs }?.second

    @SuppressLint("MissingPermission")
    fun requestLocation() {
        if (!hasLocationPermission()) return
        val manager = locationManager ?: return
        val providers = listOf(SystemLocationManager.GPS_PROVIDER, SystemLocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return

        try {
            // A recent cached fix gets the alert a location straight away;
            // the fresh request below replaces it when it lands.
            providers.mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { onLocation(it) }

            for (provider in providers) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    manager.getCurrentLocation(provider, CancellationSignal(), ContextCompat.getMainExecutor(context)) { location ->
                        location?.let { onLocation(it) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, object : LocationListener {
                        override fun onLocationChanged(location: Location) = onLocation(location)
                    }, Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to request location: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Location request failed: ${e.message}")
        }
    }

    private fun onLocation(location: Location) {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        val fix = Fix(location.latitude, location.longitude, System.currentTimeMillis() - ageMs)
        // Providers answer in any order; keep the newest.
        if ((currentFix?.timeMs ?: Long.MIN_VALUE) >= fix.timeMs) return
        currentFix = fix
        reverseGeocode(fix)
    }

    private fun reverseGeocode(fix: Fix) {
        scope.launch {
            val address = withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.getDefault()).getFromLocation(fix.latitude, fix.longitude, 1)
                        ?.firstOrNull()?.getAddressLine(0)
                } catch (e: Exception) {
                    null
                }
            } ?: String.format(Locale.US, "%.4f, %.4f", fix.latitude, fix.longitude)
            // A newer fix may have arrived while geocoding; don't pair its
            // location with this older address.
            if (currentFix?.timeMs == fix.timeMs) currentAddress = fix.timeMs to address
        }
    }

    private companion object {
        const val TAG = "LocationManager"
    }
}
