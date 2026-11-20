package fyi.justfreesoftware.justcapture

import fyi.justfreesoftware.justcapture.model.Fix
import fyi.justfreesoftware.justcapture.model.RecordingSession
import fyi.justfreesoftware.justcapture.model.SessionLocationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLocationInfoTest {

    @Test
    fun readsBackWhatASessionWrites() {
        val start = 1_700_000_000_000L
        val session = RecordingSession(id = "s", startEpochMs = start, locationAddress = "1 Main St, Springfield")
            .addingStartToTrail(Fix(42.123456, -71.654321, start, accuracyMeters = 12.0), nowMs = start)
            .addingClipToTrail(Fix(42.2, -71.7, start, accuracyMeters = 30.0), "chunk_0.mp4", nowMs = start + 60_000)
            .addingClipToTrail(null, "chunk_1.mp4", nowMs = start + 120_000)

        val info = SessionLocationInfo.parse(session.locationText!!)!!

        assertNotNull(info.started)
        assertEquals("1 Main St, Springfield", info.startingAddress)
        assertEquals(listOf("Recording start", "Clip 1", "Clip 2"), info.points.map { it.label })

        val first = info.points[0]
        assertEquals(42.123456, first.latitude!!, 1e-9)
        assertEquals(-71.654321, first.longitude!!, 1e-9)
        assertEquals("within 12 m", first.accuracy)
        assertFalse(first.isLastKnown)
        assertEquals("https://www.google.com/maps?q=42.123456,-71.654321", first.mapUrl)

        // The fix was a minute old by the time the first clip ended.
        assertTrue(info.points[1].isLastKnown)

        val missing = info.points[2]
        assertNull(missing.latitude)
        assertNull(missing.mapUrl)
    }

    @Test
    fun skipsLinesItDoesNotRecognize() {
        val text = """
            Recording started: Nov 14, 2023 at 5:13:20 PM EST
            Something a newer version added: yes

            Location trail (oldest first):
            Nov 14, 2023 at 5:13:20 PM EST | recording start | 200.0, 10.0 | https://example.com
            Nov 14, 2023 at 5:14:20 PM EST | intro.mp4 | 10.5, 20.25
        """.trimIndent()

        val info = SessionLocationInfo.parse(text)!!

        assertEquals("Nov 14, 2023 at 5:13:20 PM EST", info.started)
        assertNull(info.startingAddress)
        assertEquals(2, info.points.size)
        // An impossible latitude isn't taken as a position.
        assertNull(info.points[0].latitude)
        assertEquals("intro.mp4", info.points[1].label)
        assertEquals(20.25, info.points[1].longitude!!, 1e-9)
    }

    @Test
    fun nothingRecognizableIsNoInfo() {
        assertNull(SessionLocationInfo.parse(""))
        assertNull(SessionLocationInfo.parse("just some text\nand more"))
    }
}
