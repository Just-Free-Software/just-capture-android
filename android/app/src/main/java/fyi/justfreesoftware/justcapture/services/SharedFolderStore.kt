package fyi.justfreesoftware.justcapture.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A folder someone else shared by link, saved for replay on this phone. */
@Serializable
data class SharedFolder(
    val id: String,
    val name: String,
    val addedAtMs: Long,
    val downloadedClipIds: List<String> = emptyList(),
    /** Clips seen on Drive at the last check. */
    val remoteClipIds: List<String> = emptyList(),
    val lastCheckedAtMs: Long? = null
) {
    val newClipCount: Int
        get() {
            val downloaded = downloadedClipIds.toSet()
            return remoteClipIds.count { it !in downloaded }
        }
}

/**
 * The Watch tab's list of saved folders, and the check for new clips in them.
 *
 * Checking costs one Drive listing request per folder. It only runs when the
 * user opens the tab or pulls to refresh, folders checked recently are
 * skipped, folders are checked one at a time, and the first rate-limit error
 * ends the whole run.
 */
class SharedFolderStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val storageFile = File(context.filesDir, "shared-folders.json")

    private val _folders = MutableStateFlow<List<SharedFolder>>(emptyList())
    val folders: StateFlow<List<SharedFolder>> = _folders.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _checkError = MutableStateFlow<String?>(null)
    val checkError: StateFlow<String?> = _checkError.asStateFlow()

    init {
        if (storageFile.exists()) {
            try {
                _folders.value = json.decodeFromString<List<SharedFolder>>(storageFile.readText())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load shared folders", e)
            }
        }
    }

    /**
     * Records what a player learned about a folder: adds it the first time
     * its name resolves, and updates its clip counts after that.
     */
    suspend fun update(downloader: DriveSessionDownloader) {
        if (downloader.source != DriveSessionDownloader.Source.PUBLIC_LINK) return
        val name = downloader.folderName ?: return
        val downloaded = downloader.segments.map { it.fileId }
        val remote = downloader.remoteClipIds
        val existing = _folders.value.firstOrNull { it.id == downloader.folderId }
        _folders.value = if (existing != null) {
            _folders.value.map {
                if (it.id != downloader.folderId) it
                else it.copy(
                    name = name,
                    downloadedClipIds = downloaded,
                    remoteClipIds = remote ?: it.remoteClipIds,
                    lastCheckedAtMs = if (remote != null) downloader.lastCheckedAtMs else it.lastCheckedAtMs
                )
            }
        } else {
            listOf(
                SharedFolder(
                    id = downloader.folderId,
                    name = name,
                    addedAtMs = System.currentTimeMillis(),
                    downloadedClipIds = downloaded,
                    remoteClipIds = remote ?: emptyList(),
                    lastCheckedAtMs = downloader.lastCheckedAtMs
                )
            ) + _folders.value
        }
        save()
    }

    suspend fun remove(folder: SharedFolder) {
        withContext(Dispatchers.IO) {
            DriveSessionDownloader.deleteDownloads(context, folder.id, DriveSessionDownloader.Source.PUBLIC_LINK)
        }
        _folders.value = _folders.value.filterNot { it.id == folder.id }
        save()
    }

    fun dismissCheckError() {
        _checkError.value = null
    }

    /** Lists each saved folder on Drive to see if clips were added, skipping recently checked ones. */
    suspend fun checkForNewClips(minimumIntervalMs: Long) {
        if (_isChecking.value) return
        _isChecking.value = true
        _checkError.value = null
        try {
            val now = System.currentTimeMillis()
            val due = _folders.value.filter { folder ->
                folder.lastCheckedAtMs?.let { now - it >= minimumIntervalMs } ?: true
            }
            for (folder in due) {
                try {
                    val files = GoogleDriveService.listFilesInFolder(folder.id, access = GoogleDriveService.ReadAccess.PublicLink)
                    val clipIds = files.filter { it.name.endsWith(".mp4") }.map { it.id }
                    _folders.value = _folders.value.map {
                        if (it.id == folder.id) it.copy(remoteClipIds = clipIds, lastCheckedAtMs = System.currentTimeMillis()) else it
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DriveException) {
                    _checkError.value = e.message
                    // These would fail the same way for every other folder.
                    if (e is DriveException.RateLimited || e is DriveException.ApiKeyMissing ||
                        e is DriveException.ApiKeyRejected
                    ) break
                } catch (e: Exception) {
                    // Usually offline; every other folder would fail the same way.
                    _checkError.value = e.message ?: "Couldn't reach Google Drive."
                    break
                }
            }
            save()
        } finally {
            _isChecking.value = false
        }
    }

    private suspend fun save() {
        val snapshot = _folders.value
        withContext(Dispatchers.IO) {
            try {
                val tmp = File(storageFile.path + ".tmp")
                tmp.writeText(json.encodeToString(snapshot))
                tmp.renameTo(storageFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save shared folders", e)
            }
        }
    }

    private companion object {
        const val TAG = "SharedFolderStore"
    }
}
