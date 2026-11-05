package fyi.justfreesoftware.justcapture

import fyi.justfreesoftware.justcapture.config.EmailSettings
import fyi.justfreesoftware.justcapture.model.Fix
import fyi.justfreesoftware.justcapture.model.LocationPoint
import fyi.justfreesoftware.justcapture.model.RecordingSession
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTrailTest {
    private val start = 1_800_000_000_000L
    private val fix = Fix(42.36, -71.058, start, accuracyMeters = 12.4)
    private val session = RecordingSession(id = "s", startEpochMs = start)

    @Test
    fun `no location means no file`() {
        assertNull(session.locationText)
        val onlyGaps = session.addingClipToTrail(null, "chunk_0.mp4", start)
        assertNull(onlyGaps.locationText)
    }

    @Test
    fun `start is recorded once`() {
        val trail = session.addingStartToTrail(fix, start).addingStartToTrail(fix.copy(latitude = 1.0), start).locationTrail!!
        assertEquals(1, trail.size)
        assertEquals(42.36, trail[0].latitude!!, 0.0)
        assertNull(trail[0].clipFileName)
    }

    @Test
    fun `trail lines state accuracy, staleness and gaps`() {
        val text = session
            .addingStartToTrail(fix, start)
            .addingClipToTrail(fix, "chunk_0.mp4", start + 10_000)
            .addingClipToTrail(fix, "chunk_1.mp4", start + 60_000)
            .addingClipToTrail(null, "chunk_2.mp4", start + 70_000)
            .locationText!!
        val lines = text.lines()
        assertTrue(lines[0].startsWith("Recording started: "))
        assertEquals("Location trail (oldest first):", lines[2])
        assertTrue(lines[3], lines[3].endsWith("| recording start | 42.360000, -71.058000 | within 12 m | https://www.google.com/maps?q=42.360000,-71.058000"))
        assertFalse(lines[4], lines[4].contains("last known"))
        assertTrue(lines[5], lines[5].contains("| chunk_1.mp4 | last known position | 42.360000"))
        assertTrue(lines[6], lines[6].endsWith("| chunk_2.mp4 | no location available"))
    }

    @Test
    fun `start location alone still produces a file`() {
        val text = session.copy(latitude = 42.36, longitude = -71.058, locationAddress = "1 Main St").locationText!!
        assertTrue(text.contains("Starting address: 1 Main St"))
        assertTrue(text.contains("Unknown time | recording start | 42.360000, -71.058000"))
    }

    @Test
    fun `tiny coordinates avoid scientific notation`() {
        assertEquals("https://www.google.com/maps?q=0.000100,-0.000010", LocationPoint.mapUrl(0.0001, -0.00001))
    }

    @Test
    fun `approximate address scales its radius`() {
        assertEquals("Near Boston, MA (within about 12 km)", LocationPoint.approximateAddress("Boston, MA", 12_400.0))
        assertEquals("Near Boston, MA (within about 250 m)", LocationPoint.approximateAddress("Boston, MA", 250.0))
    }

    @Test
    fun `new sessions stay private until someone is notified`() {
        assertNull(RecordingSession.new(10.0).driveFolderShared)
    }

    @Test
    fun `sessions saved by earlier builds still load`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<RecordingSession>("""{"id":"s","startEpochMs":1,"driveFolderShared":true}""")
        assertEquals(true, old.driveFolderShared)
        assertNull(old.locationTrail)
    }

    @Test
    fun `earlier default subjects are upgraded but custom ones are kept`() {
        assertTrue(EmailSettings.isDefaultSubject("[your_name] started recording"))
        assertTrue(EmailSettings.isDefaultSubject("Alert! [your_name] is broadcasting"))
        assertTrue(EmailSettings.isDefaultSubject("🚨 Emergency Recording Alert"))
        assertTrue(EmailSettings.isDefaultSubject(""))
        assertFalse(EmailSettings.isDefaultSubject("Check on [your_name]"))
    }
}
