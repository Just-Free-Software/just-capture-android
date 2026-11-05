package fyi.justfreesoftware.justcapture.services

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Downloads a session folder from Google Drive (the user's own, or one
 * someone shared as "anyone with the link") so its clips can be played back
 * to back.
 *
 * The playlist's entries point at authenticated Drive URLs and its segments
 * are standalone MP4s rather than real HLS segments, so a player can't stream
 * it as-is. Instead the clips are downloaded (in playlist order) and played
 * as a playlist. A manifest saved next to the clips lets them play again
 * later without touching the network.
 *
 * Nothing here runs on its own: every Drive request comes from an explicit
 * call, downloads run one at a time, and a failure stops the sync rather
 * than retrying. Clips already on the phone are never fetched again.
 */
class DriveSessionDownloader(
    context: Context,
    val folderId: String,
    val source: Source
) {
    enum class Source {
        /** A folder this app uploaded, read as the signed-in user. */
        OWN_DRIVE,

        /** A folder shared as "anyone with the link", read anonymously with the app's API key. */
        PUBLIC_LINK
    }

    sealed interface SyncState {
        data object Idle : SyncState
        data object Checking : SyncState
        data class Downloading(val completed: Int, val total: Int) : SyncState
        data object Preparing : SyncState
        data class Failed(val message: String) : SyncState

        val isBusy: Boolean
            get() = this is Checking || this is Downloading || this is Preparing
    }

    @Serializable
    data class Segment(val fileId: String, val fileName: String, val size: Long? = null)

    @Serializable
    private data class Manifest(val folderName: String? = null, val segments: List<Segment> = emptyList())

    var sync by mutableStateOf<SyncState>(SyncState.Idle)
        private set
    var downloadedBytes by mutableStateOf(0L)
        private set
    var totalBytes by mutableStateOf(0L)
        private set

    /** What has been downloaded, in playback order. */
    var segments by mutableStateOf<List<Segment>>(emptyList())
        private set

    /** The folder's name on Drive. */
    var folderName by mutableStateOf<String?>(null)
        private set

    /** Clips on Drive that aren't on the phone yet, as of the last check. */
    var newClipIds by mutableStateOf<List<String>>(emptyList())
        private set

    /** Every clip on Drive as of the last check, or null if never checked. */
    var remoteClipIds by mutableStateOf<List<String>?>(null)
        private set
    var lastCheckedAtMs by mutableStateOf<Long?>(null)
        private set

    /** Readable clips on the phone, in playback order. Grows as new clips arrive. */
    var playableFiles by mutableStateOf<List<File>>(emptyList())
        private set

    /** Clips that downloaded but couldn't be read, left out of playback. */
    var skippedClips by mutableStateOf(0)
        private set

    val directory: File = directory(context, folderId, source)
    private val manifestFile = File(directory, "manifest.json")

    init {
        loadManifest(manifestFile)?.let {
            segments = it.segments
            folderName = it.folderName
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Shared folders are kept until the user deletes them. The user's own
         * sessions already live in their Drive, so those sit in the cache
         * where Android may reclaim them.
         */
        fun directory(context: Context, folderId: String, source: Source): File = when (source) {
            Source.PUBLIC_LINK -> File(context.filesDir, "SharedFolders/$folderId")
            Source.OWN_DRIVE -> File(context.cacheDir, "DriveDownloads/$folderId")
        }

        fun deleteDownloads(context: Context, folderId: String, source: Source) {
            directory(context, folderId, source).deleteRecursively()
        }

        private fun loadManifest(file: File): Manifest? =
            runCatching { json.decodeFromString<Manifest>(file.readText()) }.getOrNull()

        /**
         * Pulls Drive file ids out of the playlist's
         * `https://www.googleapis.com/drive/v3/files/<id>?alt=media` entries.
         */
        fun segmentFileIds(playlist: String): List<String> =
            playlist.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val path = line.substringBefore('?').split('/')
                    val index = path.lastIndexOf("files")
                    path.getOrNull(index + 1)?.takeIf { index >= 0 && it.isNotEmpty() }
                }
                .toList()

        private fun chunkIndex(name: String): Int =
            name.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
    }

    private suspend fun saveManifest() = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val tmp = File(directory, "manifest.json.tmp")
        tmp.writeText(json.encodeToString(Manifest(folderName, segments)))
        tmp.renameTo(manifestFile)
        // A copy of the playlist next to the clips, pointing at the local
        // files, so the downloaded folder is self-describing.
        File(directory, "playlist.m3u8").writeText(buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-MEDIA-SEQUENCE:0\n")
            segments.forEach { append("#EXTINF:0,\n${it.fileName}\n") }
            append("#EXT-X-ENDLIST\n")
        })
    }

    // ---- Playback ----

    /** Builds the playable list from clips already on the phone. No network. */
    suspend fun loadSaved() {
        if (segments.isNotEmpty()) rebuildPlayable()
    }

    /**
     * A clip cut off mid-upload or mid-recording shouldn't block the rest of
     * the session from playing, so unreadable files are left out.
     */
    private suspend fun rebuildPlayable() {
        val (readable, skipped) = withContext(Dispatchers.IO) {
            val files = segments.map { File(directory, it.fileName) }.filter { it.exists() }
            val ok = files.filter { isReadableVideo(it) }
            ok to (files.size - ok.size)
        }
        skippedClips = skipped
        playableFiles = readable
    }

    private fun isReadableVideo(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            hasVideo && duration > 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---- Syncing with Drive ----

    /** Fetched fresh per request, since a long download can outlive a token. */
    private suspend fun access(authManager: GoogleAuthManager): GoogleDriveService.ReadAccess = when (source) {
        Source.OWN_DRIVE -> GoogleDriveService.ReadAccess.User(authManager.validAccessToken())
        Source.PUBLIC_LINK -> GoogleDriveService.ReadAccess.PublicLink
    }

    private fun describe(e: Exception): String = e.message ?: "Couldn't reach Google Drive."

    /** One listing request: finds out whether the folder has new clips. Downloads nothing. */
    suspend fun checkForNewClips(authManager: GoogleAuthManager) {
        if (sync.isBusy) return
        sync = SyncState.Checking
        sync = try {
            resolveFolderName(authManager)
            recordRemote(GoogleDriveService.listFilesInFolder(folderId, access = access(authManager)))
            SyncState.Idle
        } catch (e: CancellationException) {
            sync = SyncState.Idle
            throw e
        } catch (e: Exception) {
            SyncState.Failed(describe(e))
        }
    }

    /** Downloads every clip that isn't on the phone yet, then adds them to playback. */
    suspend fun downloadNewClips(authManager: GoogleAuthManager) {
        if (sync.isBusy) return
        sync = SyncState.Checking
        try {
            resolveFolderName(authManager)
            val files = GoogleDriveService.listFilesInFolder(folderId, access = access(authManager))
            recordRemote(files)
            val ordered = orderedSegments(files, authManager)
            if (ordered.isEmpty()) {
                sync = SyncState.Failed("This folder has no clips yet.")
                return
            }

            withContext(Dispatchers.IO) { directory.mkdirs() }
            val missing = ordered.filterNot { isAlreadyDownloaded(it) }
            totalBytes = missing.sumOf { it.size ?: 0L }
            downloadedBytes = 0

            for ((index, segment) in missing.withIndex()) {
                sync = SyncState.Downloading(index, missing.size)
                val destination = File(directory, segment.fileName)
                // Stops at the first failure: a throttled download is never
                // retried automatically.
                GoogleDriveService.downloadFile(segment.fileId, destination, access(authManager))
                downloadedBytes += segment.size ?: destination.length()
                // Saved per clip, so an interrupted sync keeps what it got.
                segments = ordered.filter { isAlreadyDownloaded(it) }
                saveManifest()
            }
            segments = ordered.filter { isAlreadyDownloaded(it) }
            saveManifest()
            newClipIds = emptyList()

            sync = SyncState.Preparing
            rebuildPlayable()
            sync = SyncState.Idle
        } catch (e: CancellationException) {
            sync = SyncState.Idle
            throw e
        } catch (e: Exception) {
            sync = SyncState.Failed(describe(e))
        }
        // Whatever finished downloading before a failure is still playable.
        if (playableFiles.isEmpty() && segments.isNotEmpty()) rebuildPlayable()
    }

    private suspend fun resolveFolderName(authManager: GoogleAuthManager) {
        // For shared links this also catches a wrong or private link before
        // anything else is requested.
        if (folderName != null) return
        folderName = GoogleDriveService.getFile(folderId, access(authManager)).name
    }

    private fun recordRemote(files: List<DriveFile>) {
        val clipIds = files.filter { it.name.endsWith(".mp4") }.map { it.id }
        val downloaded = segments.map { it.fileId }.toSet()
        remoteClipIds = clipIds
        newClipIds = clipIds.filterNot { it in downloaded }
        lastCheckedAtMs = System.currentTimeMillis()
    }

    /**
     * Clip order comes from `playlist.m3u8` when there is one. Without it
     * (e.g. the playlist upload failed) the folder's `chunk_N.mp4` files are
     * sorted by N.
     */
    private suspend fun orderedSegments(files: List<DriveFile>, authManager: GoogleAuthManager): List<Segment> {
        val byId = files.associateBy { it.id }
        val byChunkIndex = files
            .filter { it.name.endsWith(".mp4") }
            .sortedBy { chunkIndex(it.name) }
            .map { Segment(it.id, it.name, it.size) }

        val playlist = files.firstOrNull { it.name == "playlist.m3u8" } ?: return byChunkIndex
        val ids = segmentFileIds(GoogleDriveService.downloadText(playlist.id, access(authManager)))
        if (ids.isEmpty()) return byChunkIndex

        val ordered = ids.map { id ->
            val file = byId[id]
            Segment(id, file?.name ?: "$id.mp4", file?.size)
        }
        // The playlist is rewritten after each upload, so a clip can be in
        // the folder before the playlist lists it. Don't leave it out.
        val listed = ids.toSet()
        return ordered + byChunkIndex.filterNot { it.fileId in listed }
    }

    private fun isAlreadyDownloaded(segment: Segment): Boolean {
        val file = File(directory, segment.fileName)
        if (!file.exists()) return false
        return segment.size == null || file.length() == segment.size
    }
}
