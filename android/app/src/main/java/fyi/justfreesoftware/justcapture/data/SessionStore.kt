package fyi.justfreesoftware.justcapture.data

import android.content.Context
import android.util.Log
import fyi.justfreesoftware.justcapture.model.RecordingSession
import fyi.justfreesoftware.justcapture.model.VideoChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent store for recording sessions, persisted as sessions.json in the
 * app's private storage (mirrors the iOS app's documents/sessions.json).
 *
 * All mutations happen on a single-threaded store context so state updates and
 * file writes are serialized. The UI observes [sessions] as an immutable list;
 * mutations produce updated copies.
 */
class SessionStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val filesDir: File = context.filesDir
    private val storageFile = File(context.filesDir, "sessions.json")
    private val storeContext = Dispatchers.IO.limitedParallelism(1)

    private val _sessions = MutableStateFlow<List<RecordingSession>>(emptyList())
    val sessions: StateFlow<List<RecordingSession>> = _sessions.asStateFlow()

    val currentSession: RecordingSession?
        get() = _sessions.value.firstOrNull { it.isActive } ?: _sessions.value.firstOrNull()

    init {
        // One small JSON file at app start; blocking load keeps launch ordering simple.
        runBlocking { load() }
    }

    suspend fun add(session: RecordingSession) {
        withContext(storeContext) {
            _sessions.value = listOf(session) + _sessions.value
            persist()
        }
    }

    suspend fun appendChunk(sessionId: String, chunk: VideoChunk) {
        update(sessionId) { session ->
            session.copy(chunks = session.chunks + chunk)
        }
    }

    suspend fun update(sessionId: String, transform: (RecordingSession) -> RecordingSession) {
        withContext(storeContext) {
            _sessions.value = _sessions.value.map { if (it.id == sessionId) transform(it) else it }
            persist()
        }
    }

    suspend fun updateChunk(
        sessionId: String,
        chunkId: String,
        transform: (VideoChunk) -> VideoChunk
    ) {
        update(sessionId) { session ->
            session.copy(chunks = session.chunks.map { if (it.id == chunkId) transform(it) else it })
        }
    }

    /**
     * Marks the active session inactive (used when a recording stops). Returns
     * the session that was finalized, or null if there was no active session.
     */
    suspend fun finalizeActive(): RecordingSession? {
        var finalized: RecordingSession? = null
        withContext(storeContext) {
            _sessions.value = _sessions.value.map { session ->
                if (session.isActive) {
                    val done = session.copy(isActive = false)
                    finalized = done
                    done
                } else {
                    session
                }
            }
            if (finalized != null) persist()
        }
        return finalized
    }

    /**
     * Removes a session from disk: deletes its local recording directory and
     * drops it from the in-memory list. Callers are responsible for cancelling
     * any in-flight uploads and removing related email jobs before calling
     * this so nothing races back in and re-creates state.
     */
    suspend fun delete(sessionId: String) {
        withContext(storeContext) {
            val recordingsDir = File(filesDir, "recordings/$sessionId")
            recordingsDir.deleteRecursively()
            _sessions.value = _sessions.value.filterNot { it.id == sessionId }
            persist()
        }
    }

    suspend fun save() {
        withContext(storeContext) { persist() }
    }

    private suspend fun load() {
        withContext(storeContext) {
            if (!storageFile.exists()) return@withContext
            try {
                val decoded = json.decodeFromString<List<RecordingSession>>(storageFile.readText())
                // Any session flagged active on disk was interrupted by a kill/relaunch;
                // finalize it so it stops appearing as "in progress".
                val hadActive = decoded.any { it.isActive }
                _sessions.value = decoded.map { if (it.isActive) it.copy(isActive = false) else it }
                if (hadActive) persist()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sessions", e)
            }
        }
    }

    private fun persist() {
        try {
            storageFile.writeText(json.encodeToString(_sessions.value))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sessions", e)
        }
    }

    private companion object {
        const val TAG = "SessionStore"
    }
}
