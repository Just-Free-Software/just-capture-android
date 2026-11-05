package fyi.justfreesoftware.justcapture.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import fyi.justfreesoftware.justcapture.model.Fix
import fyi.justfreesoftware.justcapture.model.LocationPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import android.location.LocationManager as SystemLocationManager

/**
 * Location for recordings and emails: one-shot requests before recording, and
 * continuous tracking during it. The current fix is exposed as Compose state;
 * the address is reverse geocoded (falling back to raw coordinates) and kept
 * paired with the fix it came from.
 *
 * [isEnabled] is the app's "Save location" setting. While it's off, nothing is
 * fetched. Everything here runs on the main thread.
 */
class LocationManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean
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

    /** Receives the first usable fix of a recording, then is cleared. */
    private var onFirstUsableFix: ((Fix) -> Unit)? = null

    /** Best recent fix, read each time a clip ends. Not state, since no view shows it. */
    private var latestUsableFix: Fix? = null
    private var trackingStartedAtMs: Long? = null
    private var lastAddressedFixAtMs: Long? = null
    private val trackingListener = FixListener()

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * The providers this app may use. GPS needs the fine permission, so asking
     * it for a location with only "approximate" granted would throw and lose
     * the network location that is allowed.
     */
    private fun permittedProviders(manager: SystemLocationManager): List<String> {
        val providers = buildList {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) add(SystemLocationManager.GPS_PROVIDER)
            if (hasLocationPermission()) add(SystemLocationManager.NETWORK_PROVIDER)
        }
        val available = manager.allProviders
        return providers.filter { it in available }
    }

    fun refreshPermission() {
        val wasGranted = permissionGranted
        val isGranted = hasLocationPermission()
        permissionGranted = isGranted
        if (!isGranted || !isEnabled()) return
        if (trackingStartedAtMs != null) {
            registerTrackingUpdates()
        } else if (!wasGranted) {
            // Warm up a fix as soon as we're allowed to, so the first email
            // isn't sent before any location is known.
            requestLocation()
        }
    }

    /**
     * Returns the current fix only if it was taken within [maxAgeMs]. An old
     * fix from a previous launch could be miles away, which is worse than no
     * location in an email.
     */
    fun freshFix(maxAgeMs: Long = 120_000): Fix? =
        currentFix?.takeIf { System.currentTimeMillis() - it.timeMs <= maxAgeMs }

    /** The geocoded address, only if it belongs to the given fix. */
    fun address(fix: Fix): String? = currentAddress?.takeIf { it.first == fix.timeMs }?.second

    /** Keeps location running until [stopTracking], for the recording's trail. */
    fun startTracking(onFirstUsableFix: (Fix) -> Unit) {
        this.onFirstUsableFix = onFirstUsableFix
        latestUsableFix = null
        trackingStartedAtMs = System.currentTimeMillis()
        lastAddressedFixAtMs = null
        registerTrackingUpdates()
        // Gives the first email something to use straight away; a fix from
        // before recording is replaced as soon as a newer one lands.
        lastKnownSystemLocation()?.let { onLocation(it) }
    }

    /**
     * Keeps the newest fix, because the last clip finishes writing just after
     * recording stops and still needs a location.
     */
    fun stopTracking() {
        onFirstUsableFix = null
        trackingStartedAtMs = null
        locationManager?.removeUpdates(trackingListener)
    }

    /** Where the phone is now, or null if there's been no usable fix recently. */
    fun recentUsableFix(): Fix? =
        latestUsableFix?.takeIf { System.currentTimeMillis() - it.timeMs <= LocationPoint.CURRENT_FIX_MAX_AGE_MS }

    /**
     * The newest position known, however old, including one the system
     * remembers from before this recording. A clip's trail line says if it's
     * not current.
     */
    val lastKnownFix: Fix?
        get() = latestUsableFix ?: lastKnownSystemLocation()?.takeIf { it.hasAccuracy() }?.toFix()

    @SuppressLint("MissingPermission")
    private fun lastKnownSystemLocation(): Location? {
        val manager = locationManager ?: return null
        return permittedProviders(manager)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    @SuppressLint("MissingPermission")
    private fun registerTrackingUpdates() {
        val manager = locationManager ?: return
        // Disabled providers are included on purpose: if the user turns GPS on
        // mid-recording, its fixes start arriving without registering again.
        for (provider in permittedProviders(manager)) {
            try {
                manager.requestLocationUpdates(provider, TRACKING_INTERVAL_MS, 0f, trackingListener, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't track location with $provider: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun requestLocation() {
        if (!isEnabled()) return
        val manager = locationManager ?: return
        val providers = permittedProviders(manager)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return

        try {
            // A recent cached fix gets the email a location straight away;
            // the fresh request below replaces it when it lands.
            lastKnownSystemLocation()?.let { onLocation(it) }

            for (provider in providers) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    manager.getCurrentLocation(provider, CancellationSignal(), ContextCompat.getMainExecutor(context)) { location ->
                        location?.let { onLocation(it) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, FixListener(), Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to request location: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Location request failed: ${e.message}")
        }
    }

    private fun Location.toFix(): Fix {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
        return Fix(
            latitude = latitude,
            longitude = longitude,
            timeMs = System.currentTimeMillis() - ageMs,
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null
        )
    }

    private fun onLocation(location: Location) {
        val isTracking = trackingStartedAtMs != null
        if (isTracking && !isEnabled()) {
            // "Save location" was turned off mid-recording.
            stopTracking()
            return
        }
        val fix = location.toFix()
        // A location without an accuracy can't be labeled, so it's left off the trail.
        if (isTracking && location.hasAccuracy()) {
            if (isBetter(fix, latestUsableFix)) latestUsableFix = fix
            // The system may hand over an old cached fix first; that isn't the start.
            recentUsableFix()?.let { recent ->
                onFirstUsableFix?.invoke(recent)
                onFirstUsableFix = null
            }
        }
        if (isBetter(fix, currentFix) && shouldUpdateCurrentFix(fix)) {
            lastAddressedFixAtMs = fix.timeMs
            currentFix = fix
            reverseGeocode(fix)
        }
    }

    /**
     * GPS and network answer separately, so a rough network fix can arrive a
     * moment after a precise GPS one. It only takes over once the precise one
     * has gone stale.
     */
    private fun isBetter(fix: Fix, held: Fix?): Boolean {
        if (held == null) return true
        if (fix.timeMs <= held.timeMs) return false
        val heldAccuracy = held.accuracyMeters ?: return true
        val accuracy = fix.accuracyMeters ?: return false
        return accuracy <= heldAccuracy || fix.timeMs - held.timeMs > FIX_STALE_AFTER_MS
    }

    /**
     * While tracking, only some fixes become [currentFix], so that emails
     * waiting for a location get one whose address has been looked up.
     */
    private fun shouldUpdateCurrentFix(fix: Fix): Boolean {
        val startedAt = trackingStartedAtMs ?: return true
        val lastAddressedAt = lastAddressedFixAtMs ?: return true
        // Replace a fix from before recording started as soon as possible.
        if (lastAddressedAt < startedAt) return fix.timeMs >= startedAt
        // A street-level fix is worth a lookup as soon as there is one.
        if (currentFix.isRough() && !fix.isRough()) return true
        return fix.timeMs - lastAddressedAt >= TRACKING_ADDRESS_INTERVAL_MS
    }

    private fun Fix?.isRough(): Boolean = (this?.accuracyMeters ?: 0.0) > LocationPoint.STREET_ADDRESS_MAX_ACCURACY_M

    private fun reverseGeocode(fix: Fix) {
        scope.launch {
            val place = withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.getDefault()).getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull()
                } catch (e: Exception) {
                    null
                }
            }
            val address = describe(fix, place)
            // A newer fix may have arrived while geocoding; don't pair its
            // location with this older address.
            if (currentFix?.timeMs == fix.timeMs) currentAddress = fix.timeMs to address
        }
    }

    /** A rough fix gets only its general area, since a street address would overstate it. */
    private fun describe(fix: Fix, place: Address?): String {
        val coordinates = String.format(Locale.US, "%.4f, %.4f", fix.latitude, fix.longitude)
        val accuracy = fix.accuracyMeters
        if (accuracy == null || accuracy <= LocationPoint.STREET_ADDRESS_MAX_ACCURACY_M) {
            return place?.getAddressLine(0) ?: coordinates
        }
        val area = listOfNotNull(place?.locality ?: place?.subAdminArea, place?.adminArea)
            .joinToString(", ")
            .ifEmpty { place?.countryName ?: coordinates }
        return LocationPoint.approximateAddress(area, accuracy)
    }

    /**
     * Implements every callback explicitly: below Android 11 the framework
     * declares them abstract, so leaving them to the SDK's default methods
     * crashes with AbstractMethodError when one is called.
     */
    private inner class FixListener : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private companion object {
        const val TAG = "LocationManager"
        const val TRACKING_INTERVAL_MS = 1_000L

        /** A more precise fix older than this gives way to a rougher new one. */
        const val FIX_STALE_AFTER_MS = 10_000L

        /** Addresses are looked up this often at most while tracking, since only the start address is used. */
        const val TRACKING_ADDRESS_INTERVAL_MS = 60_000L
    }
}
