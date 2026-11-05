package fyi.justfreesoftware.justcapture

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import fyi.justfreesoftware.justcapture.model.VideoChunk
import fyi.justfreesoftware.justcapture.services.CameraManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Records for real on the device's camera: timed cuts, a manual cut and a
 * camera flip, then checks every clip is a complete MP4 that starts on a
 * keyframe and whose length matches what the recorder reported.
 */
@RunWith(AndroidJUnit4::class)
class ChunkRecordingTest {

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private data class FileInfo(val videoSamples: Int, val firstIsSync: Boolean, val durationSec: Double, val hasAudio: Boolean)

    private fun inspect(file: File): FileInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            var videoTrack = -1
            var hasAudio = false
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    durationUs = format.getLong(MediaFormat.KEY_DURATION)
                }
                if (mime.startsWith("audio/")) hasAudio = true
            }
            assertTrue("${file.name} has no video track", videoTrack >= 0)
            extractor.selectTrack(videoTrack)
            val firstIsSync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            var samples = 0
            while (extractor.sampleTime >= 0) {
                samples++
                extractor.advance()
            }
            return FileInfo(samples, firstIsSync, durationUs / 1_000_000.0, hasAudio)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun recordsGaplessChunksAcrossCutsAndFlip() {
        val directory = File(context.cacheDir, "chunk-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val chunks = CopyOnWriteArrayList<VideoChunk>()
        val scope = MainScope()
        val owner = TestOwner()
        val camera = onMain {
            CameraManager(context, scope).also {
                it.onChunkCompleted = { chunk -> chunks += chunk }
                owner.registry.currentState = Lifecycle.State.RESUMED
                it.setupSession(owner)
            }
        }

        Thread.sleep(2_000)
        onMain { camera.startRecording(5.0, directory) }
        Thread.sleep(7_500) // one timed cut at 5 s
        onMain { camera.cutChunkNow() }
        Thread.sleep(3_000)
        onMain { camera.flipCamera() }
        Thread.sleep(7_000) // another timed cut on the front camera
        onMain { camera.stopRecording() }

        val deadline = System.currentTimeMillis() + 15_000
        while (onMain { camera.isFinishing } && System.currentTimeMillis() < deadline) Thread.sleep(100)
        Thread.sleep(300) // last chunk callback is posted after isFinishing clears

        val error = onMain { camera.recordingError }
        onMain {
            owner.registry.currentState = Lifecycle.State.DESTROYED
            scope.cancel()
        }

        assertNull("Recording error: $error", error)
        assertTrue("Expected at least 4 chunks, got ${chunks.size}", chunks.size >= 4)
        assertEquals((0 until chunks.size).toList(), chunks.map { it.index })

        for (chunk in chunks) {
            val file = File(chunk.localPath)
            assertTrue("${file.name} missing", file.exists())
            val info = inspect(file)
            val fps = info.videoSamples / info.durationSec
            Log.i(
                "ChunkRecordingTest",
                "chunk ${chunk.index}: reported ${"%.3f".format(chunk.durationSec)} s, file ${"%.3f".format(info.durationSec)} s, " +
                    "${info.videoSamples} frames (${"%.1f".format(fps)} fps), audio=${info.hasAudio}, ${file.length()} bytes"
            )
            assertTrue("${file.name} doesn't start on a keyframe", info.firstIsSync)
            // The file holds every frame the recorder counted for it: no
            // frames lost at either end of the cut.
            assertTrue(
                "${file.name}: file is ${info.durationSec} s but recorder reported ${chunk.durationSec} s",
                abs(info.durationSec - chunk.durationSec) < 0.15
            )
        }
        // Timed cuts land on the boundary, not a keyframe interval later.
        assertTrue("First chunk was ${chunks[0].durationSec} s", abs(chunks[0].durationSec - 5.0) < 0.3)
        assertTrue("No partial files should remain", directory.listFiles().orEmpty().none { it.name.endsWith(".partial.mp4") })
    }
}
