package com.justapp.record.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager as SystemLocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.justapp.record.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One-shot location + reverse geocoding, mirroring the iOS app's
 * LocationManager. The current fix is exposed as Compose state; the address
 * is reverse geocoded (falling back to raw coordinates).
 */
class LocationManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    var currentLatitude by mutableStateOf<Double?>(null)
        private set
    var currentLongitude by mutableStateOf<Double?>(null)
        private set
    var currentAddress by mutableStateOf<String?>(null)
        private set

    private val locationManager: SystemLocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? SystemLocationManager

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun requestLocation() {
        if (!hasLocationPermission()) return
        val manager = locationManager ?: return
        val provider = bestProvider(manager) ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(
                    provider,
                    CancellationSignal(),
                    ContextCompat.getMainExecutor(context)
                ) { location -> onLocation(location) }
            } else {
                @Suppress("MissingPermission")
                manager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            onLocation(location)
                        }
                    }
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to request location: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Location request failed: ${e.message}")
        }
    }

    private fun onLocation(location: Location) {
        currentLatitude = location.latitude
        currentLongitude = location.longitude
        reverseGeocode()
    }

    private fun reverseGeocode() {
        val lat = currentLatitude ?: return
        val lng = currentLongitude ?: return
        scope.launch {
            currentAddress = withContext(Dispatchers.IO) {
                try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(lat, lng, 1)
                    addresses?.firstOrNull()?.getAddressLine(0)
                        ?: fallbackCoordinates(lat, lng)
                } catch (e: Exception) {
                    fallbackCoordinates(lat, lng)
                }
            }
        }
    }

    private fun fallbackCoordinates(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.4f, %.4f", lat, lng)

    private fun bestProvider(manager: SystemLocationManager): String? =
        listOf(
            SystemLocationManager.GPS_PROVIDER,
            SystemLocationManager.NETWORK_PROVIDER,
            SystemLocationManager.PASSIVE_PROVIDER
        ).firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private companion object {
        const val TAG = "LocationManager"
    }
}
