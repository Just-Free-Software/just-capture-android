package fyi.justfreesoftware.justcapture.services

import android.media.MediaMetadataRetriever
import android.util.Log
import fyi.justfreesoftware.justcapture.data.SessionStore
import fyi.justfreesoftware.justcapture.model.VideoChunk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists finished clips the app died before recording in its session: the file
 * is complete, but nothing knows about it, so it would never upload. Runs once
 * per launch, before anything starts uploading.
 *
 * The clip that was still being written can't be saved. Its `.partial` file
 * has no index until the muxer finishes, so it's left where it is.
 */
object ClipRecovery {
    private const val TAG = "ClipRecovery"
    private const val MS_PER_SECOND = 1000.0
    private val FINISHED_CLIP = Regex("""chunk_(\d+)\.mp4""")

    suspend fun recoverInterruptedClips(store: SessionStore) {
        for (session in store.interruptedSessionIds.mapNotNull { store.session(it) }) {
            val listed = session.chunks.map { it.index }.toSet()
            val found = withContext(Dispatchers.IO) { unlistedClips(store.directory(session.id), listed) }
            if (found.isNotEmpty()) {
                Log.i(TAG, "Recovered ${found.size} unlisted clip(s) in session ${session.id}")
                store.update(session.id) { current ->
                    val known = current.chunks.map { it.index }.toSet()
                    current.copy(chunks = (current.chunks + found.filter { it.index !in known }).sortedBy { it.index })
                }
            }
        }
        store.clearInterruptedSessions()
    }

    private fun unlistedClips(directory: File, listed: Set<Int>): List<VideoChunk> =
        directory.listFiles().orEmpty().mapNotNull { file ->
            val index = FINISHED_CLIP.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            val durationSec = if (index == null || index in listed) null else durationSec(file)
            if (index == null || durationSec == null) {
                null
            } else {
                VideoChunk(
                    id = UUID.randomUUID().toString(),
                    index = index,
                    localPath = file.absolutePath,
                    createdAtEpochMs = file.lastModified(),
                    durationSec = durationSec,
                    fileSizeBytes = file.length()
                )
            }
        }

    /** Null when the file isn't a playable video. */
    private fun durationSec(file: File): Double? {
        val retriever = MediaMetadataRetriever()
        // An unreadable file throws rather than reporting no duration.
        val durationMs = runCatching {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.getOrNull()
        runCatching { retriever.release() }
        return durationMs?.takeIf { it > 0 }?.let { it / MS_PER_SECOND }
    }
}
