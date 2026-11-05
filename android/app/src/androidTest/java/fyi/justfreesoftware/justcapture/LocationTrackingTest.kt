package fyi.justfreesoftware.justcapture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.provider.ProviderProperties
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import fyi.justfreesoftware.justcapture.model.Fix
import fyi.justfreesoftware.justcapture.services.LocationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.location.LocationManager as SystemLocationManager

/** Drives LocationManager through the real system service with mock providers. */
@RunWith(AndroidJUnit4::class)
class LocationTrackingTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val system = context.getSystemService(Context.LOCATION_SERVICE) as SystemLocationManager
    private val providers = listOf(SystemLocationManager.GPS_PROVIDER, SystemLocationManager.NETWORK_PROVIDER)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var enabled = true
    private lateinit var activity: Activity
    private lateinit var manager: LocationManager
    private val firstFixes = mutableListOf<Fix>()

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} android:mock_location allow")
        // "While in use" location permission delivers nothing to a process
        // with no visible screen, so the tests run with the app on screen,
        // which is also the only time the app tracks location.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()
        for (provider in providers) {
            @Suppress("DEPRECATION")
            system.addTestProvider(
                provider, false, false, false, false, true, true, true,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE
            )
            system.setTestProviderEnabled(provider, true)
        }
        instrumentation.runOnMainSync { manager = LocationManager(context, scope) { enabled } }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { manager.stopTracking() }
        providers.forEach { runCatching { system.removeTestProvider(it) } }
        scope.cancel()
        activity.finish()
    }

    /** Runs a shell command and waits for it to finish. */
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun send(provider: String, latitude: Double, accuracy: Float) {
        system.setTestProviderLocation(provider, Location(provider).apply {
            this.latitude = latitude
            longitude = -71.0
            this.accuracy = accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun recent(): Fix? {
        var fix: Fix? = null
        onMain { fix = manager.recentUsableFix() }
        return fix
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun startTracking() = onMain {
        manager.startTracking { fix -> firstFixes += fix }
    }

    @Test
    fun trackingReportsTheFirstFixOnceAndLabelsItsAccuracy() {
        startTracking()
        send(SystemLocationManager.GPS_PROVIDER, 42.0, 8f)
        waitFor("the first fix") { firstFixes.any { it.latitude == 42.0 } }
        SystemClock.sleep(UPDATE_INTERVAL_MS)
        send(SystemLocationManager.GPS_PROVIDER, 42.5, 8f)
        waitFor("the second fix") { recent()?.latitude == 42.5 }
        assertEquals(1, firstFixes.count { it.latitude == 42.0 || it.latitude == 42.5 })
        assertEquals(8.0, recent()!!.accuracyMeters!!, 0.01)
    }

    @Test
    fun aRoughNetworkFixDoesNotReplaceARecentPreciseOne() {
        startTracking()
        send(SystemLocationManager.GPS_PROVIDER, 42.0, 8f)
        waitFor("the GPS fix") { recent()?.latitude == 42.0 }
        send(SystemLocationManager.NETWORK_PROVIDER, 43.0, 2000f)
        SystemClock.sleep(1_500)
        assertEquals(42.0, recent()!!.latitude, 0.0)
    }

    @Test
    fun aPreciseFixReplacesARoughOneAndItsAddressStopsSayingNear() {
        startTracking()
        send(SystemLocationManager.NETWORK_PROVIDER, 43.0, 2000f)
        waitFor("the network fix") { recent()?.latitude == 43.0 }
        var roughAddress: String? = null
        waitFor("the rough address") {
            onMain { roughAddress = manager.currentFix?.takeIf { it.latitude == 43.0 }?.let(manager::address) }
            roughAddress != null
        }
        assertTrue(roughAddress, roughAddress!!.startsWith("Near "))
        assertTrue(roughAddress, roughAddress!!.endsWith("(within about 2 km)"))

        send(SystemLocationManager.GPS_PROVIDER, 42.0, 8f)
        waitFor("the GPS fix") { recent()?.latitude == 42.0 }
        var preciseAddress: String? = null
        waitFor("the street address") {
            onMain { preciseAddress = manager.currentFix?.takeIf { it.latitude == 42.0 }?.let(manager::address) }
            preciseAddress != null
        }
        assertTrue(preciseAddress, !preciseAddress!!.startsWith("Near "))
    }

    @Test
    fun stoppingKeepsTheLastFixAndIgnoresLaterOnes() {
        startTracking()
        send(SystemLocationManager.GPS_PROVIDER, 42.0, 8f)
        waitFor("the fix") { recent()?.latitude == 42.0 }
        onMain { manager.stopTracking() }
        SystemClock.sleep(UPDATE_INTERVAL_MS)
        send(SystemLocationManager.GPS_PROVIDER, 44.0, 8f)
        SystemClock.sleep(1_500)
        var lastKnown: Fix? = null
        onMain { lastKnown = manager.lastKnownFix }
        assertEquals(42.0, assertNotNullFix(lastKnown).latitude, 0.0)
    }

    @Test
    fun turningTheSettingOffStopsTrackingAtTheNextFix() {
        startTracking()
        send(SystemLocationManager.GPS_PROVIDER, 42.0, 8f)
        waitFor("the fix") { recent()?.latitude == 42.0 }
        enabled = false
        SystemClock.sleep(UPDATE_INTERVAL_MS)
        send(SystemLocationManager.GPS_PROVIDER, 44.0, 8f)
        SystemClock.sleep(1_500)
        assertEquals(42.0, recent()!!.latitude, 0.0)
        onMain { assertNull(manager.currentFix?.takeIf { it.latitude == 44.0 }) }
    }

    @Test
    fun aOneOffRequestFetchesNothingWhileTheSettingIsOff() {
        enabled = false
        onMain { manager.requestLocation() }
        send(SystemLocationManager.GPS_PROVIDER, 42.0, 8f)
        SystemClock.sleep(1_500)
        onMain { assertNull(manager.currentFix) }
    }

    private companion object {
        /** Longer than the tracking interval, so the system doesn't drop the next fix. */
        const val UPDATE_INTERVAL_MS = 1_200L
    }

    private fun assertNotNullFix(fix: Fix?): Fix {
        assertNotNull(fix)
        return fix!!
    }
}
