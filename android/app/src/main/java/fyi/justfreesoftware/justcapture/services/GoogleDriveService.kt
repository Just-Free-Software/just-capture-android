package fyi.justfreesoftware.justcapture.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import fyi.justfreesoftware.justcapture.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Errors Drive reports that the app handles differently from a plain failure. */
sealed class DriveException(message: String) : IOException(message) {
    class StorageQuotaExceeded : DriveException("Google Drive storage is full.")

    /**
     * The account (typically a Workspace one) has an admin policy that
     * forbids sharing files with anyone outside the organization.
     */
    class PublicSharingNotAllowed : DriveException("This Google account doesn't allow sharing files publicly.")

    /** Drive throttled a read; [reason] is Drive's own name for the limit. */
    class RateLimited(reason: String) :
        DriveException("Google Drive is limiting downloads right now ($reason). Wait a while before trying again.")

    /** An anonymous read found nothing: the folder isn't shared publicly, or the link is wrong. */
    class NotPublic :
        DriveException("This folder isn't shared publicly. Ask the owner to share it as \"Anyone with the link\".")

    class ApiKeyMissing :
        DriveException("Opening shared links needs a Google API key, and none is set up in this build.")

    class ApiKeyRejected :
        DriveException("Google rejected this app's API key. Check its restrictions in Cloud Console.")

    class ReadFailed(status: Int, reason: String?) :
        DriveException("Google Drive returned an error (${reason ?: "HTTP $status"}).")
}

data class DriveStorageQuota(val limit: Long?, val usage: Long) {
    val isUnlimited: Boolean
        get() = (limit ?: 0L) <= 0L

    /** null means unlimited. */
    val availableBytes: Long?
        get() = if (isUnlimited) null else maxOf(0L, (limit ?: 0L) - usage)
}

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    /** Omitted by Drive for folders. */
    val size: Long?,
    val createdTimeMs: Long?
)

/**
 * Google Drive v3 REST calls: storage quota, folders, sharing, resumable
 * uploads, the session playlist, and reading folders back for playback.
 */
object GoogleDriveService {

    /** How a read request identifies itself to Drive. */
    sealed interface ReadAccess {
        /** As the signed-in user. With `drive.file` that only reaches files this app created. */
        data class User(val accessToken: String) : ReadAccess

        /** Anonymously, with the app's API key. Reaches files shared as "anyone with the link". */
        data object PublicLink : ReadAccess
    }

    private const val UPLOAD_CHUNK_SIZE = 2 * 1024 * 1024

    /**
     * Upper bound on pages fetched by one listing, so a misbehaving
     * `nextPageToken` can't turn into an endless stream of requests.
     */
    private const val MAX_LIST_PAGES = 5

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
    private val MP4_MEDIA_TYPE = "video/mp4".toMediaType()

    private var packageName: String? = null
    private var certSha1: String? = null

    /**
     * Records the package name and signing certificate, sent with API-key
     * requests so the key can be restricted to this app in Cloud Console.
     */
    fun init(context: Context) {
        packageName = context.packageName
        certSha1 = runCatching { signingCertSha1(context) }.getOrNull()
    }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun signingCertSha1(context: Context): String? {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return null
        return MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(it) }
    }

    private suspend fun execute(request: Request): Response =
        withContext(Dispatchers.IO) { client.newCall(request).execute() }

    private fun parse(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun errorReasons(text: String): List<String> =
        parse(text)?.get("error")?.jsonObject?.get("errors")?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject["reason"]?.jsonPrimitive?.content }.getOrNull() }
            ?: emptyList()

    private fun throwIfQuotaExceeded(bodyText: String, statusCode: Int) {
        if (statusCode == 403 && "storageQuotaExceeded" in errorReasons(bodyText)) {
            throw DriveException.StorageQuotaExceeded()
        }
    }

    suspend fun getStorageQuota(accessToken: String): DriveStorageQuota {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/about?fields=storageQuota")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        execute(request).use { response ->
            val text = response.body?.string() ?: ""
            throwIfQuotaExceeded(text, response.code)
            if (!response.isSuccessful) throw IOException("Drive about failed: ${response.code}")

            val quotaJson = parse(text)?.get("storageQuota")?.jsonObject
                ?: throw IOException("No storageQuota in response")
            val usage = quotaJson["usage"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw IOException("No usage in storageQuota")
            val limit = quotaJson["limit"]?.jsonPrimitive?.content?.toLongOrNull()
            return DriveStorageQuota(limit = limit, usage = usage)
        }
    }

    /** Returns the new folder's id and web link. */
    suspend fun createFolder(name: String, accessToken: String): Pair<String, String> {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
        }
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id,webViewLink")
            .header("Authorization", "Bearer $accessToken")
            .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        execute(request).use { response ->
            val text = response.body?.string() ?: ""
            throwIfQuotaExceeded(text, response.code)
            val parsed = parse(text)
            val id = parsed?.get("id")?.jsonPrimitive?.content
                ?: throw IOException("Folder creation failed: ${response.code}")
            val webViewLink = parsed["webViewLink"]?.jsonPrimitive?.content
                ?: throw IOException("Folder created without webViewLink")
            return id to webViewLink
        }
    }

    /**
     * Makes a file or folder readable by anyone with its link. Files inside a
     * folder inherit this, so the alert email's link opens for recipients
     * without them having to request access.
     */
    suspend fun shareWithAnyoneWithLink(fileId: String, accessToken: String) {
        val body = buildJsonObject {
            put("role", "reader")
            put("type", "anyone")
        }
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId/permissions")
            .header("Authorization", "Bearer $accessToken")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        execute(request).use { response ->
            val text = response.body?.string() ?: ""
            // A 403 is either a rate limit (reasons like userRateLimitExceeded),
            // which is worth retrying, or a sharing policy refusal, which
            // never will succeed.
            if (response.code == 403 && errorReasons(text).none { it.endsWith("Exceeded") }) {
                throw DriveException.PublicSharingNotAllowed()
            }
            if (!response.isSuccessful) throw IOException("Sharing failed: ${response.code}")
        }
    }

    suspend fun deleteFile(fileId: String, accessToken: String) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw IOException("Delete failed: ${response.code}")
        }
    }

    suspend fun uploadFile(
        file: File,
        fileName: String,
        folderId: String,
        accessToken: String,
        onProgress: (Double) -> Unit
    ): String {
        val fileSize = file.length()

        // 1. Initiate a resumable upload session.
        val metadata = buildJsonObject {
            put("name", fileName)
            put("parents", buildJsonArray { add(folderId) })
        }
        val initiate = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", "video/mp4")
            .header("X-Upload-Content-Length", fileSize.toString())
            .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val resumableUrl = execute(initiate).use { response ->
            val text = response.body?.string() ?: ""
            throwIfQuotaExceeded(text, response.code)
            response.header("Location")
                ?: throw IOException("Resumable upload initiation failed: ${response.code}")
        }

        // 2. Push the file contents in 2 MB chunks, streaming from disk.
        var fileId: String? = null
        withContext(Dispatchers.IO) {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(UPLOAD_CHUNK_SIZE)
                var offset = 0L
                while (fileId == null) {
                    coroutineContext.ensureActive()
                    val chunkLength = minOf(UPLOAD_CHUNK_SIZE.toLong(), fileSize - offset).toInt()
                    if (chunkLength > 0) {
                        raf.seek(offset)
                        raf.readFully(buffer, 0, chunkLength)
                    }
                    val builder = Request.Builder()
                        .url(resumableUrl)
                        .put(buffer.copyOf(chunkLength).toRequestBody(MP4_MEDIA_TYPE))
                    if (chunkLength > 0) {
                        builder.header("Content-Range", "bytes $offset-${offset + chunkLength - 1}/$fileSize")
                    }

                    execute(builder.build()).use { response ->
                        val text = response.body?.string() ?: ""
                        when {
                            response.code == 308 -> {
                                offset += chunkLength
                                if (fileSize > 0) onProgress(offset.toDouble() / fileSize)
                            }
                            response.isSuccessful -> {
                                fileId = parse(text)?.get("id")?.jsonPrimitive?.content
                                    ?: throw IOException("Upload finished without a file id")
                                onProgress(1.0)
                            }
                            else -> {
                                throwIfQuotaExceeded(text, response.code)
                                throw IOException("Chunk upload failed: ${response.code}")
                            }
                        }
                    }
                }
            }
        }
        return fileId!!
    }

    suspend fun uploadOrUpdatePlaylist(
        content: String,
        folderId: String,
        existingFileId: String?,
        accessToken: String
    ): String = uploadOrUpdateTextFile("playlist.m3u8", "application/x-mpegURL", content, folderId, existingFileId, accessToken)

    suspend fun uploadOrUpdateTextFile(
        name: String,
        mimeType: String,
        content: String,
        folderId: String,
        existingFileId: String?,
        accessToken: String
    ): String {
        val boundary = "Boundary-${UUID.randomUUID()}"
        val isUpdate = existingFileId != null
        val url = if (isUpdate) {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=multipart"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        }

        val metadata = buildJsonObject {
            put("mimeType", mimeType)
            if (!isUpdate) {
                put("name", name)
                put("parents", buildJsonArray { add(folderId) })
            }
        }

        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString())
            append("\r\n")
            append("--$boundary\r\n")
            append("Content-Type: $mimeType; charset=UTF-8\r\n\r\n")
            append(content)
            append("\r\n")
            append("--$boundary--")
        }.toByteArray().toRequestBody("multipart/related; boundary=$boundary".toMediaType())

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .method(if (isUpdate) "PATCH" else "POST", body)
            .build()

        execute(request).use { response ->
            val text = response.body?.string() ?: ""
            throwIfQuotaExceeded(text, response.code)
            return parse(text)?.get("id")?.jsonPrimitive?.content
                ?: throw IOException("Upload of $name failed: ${response.code}")
        }
    }

    fun getDirectDownloadUrl(fileId: String): String =
        "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

    // ---- Reading back from Drive ----

    private fun readRequest(url: HttpUrl, access: ReadAccess): Request {
        val builder = Request.Builder()
        when (access) {
            is ReadAccess.User -> {
                builder.url(url).header("Authorization", "Bearer ${access.accessToken}")
            }
            ReadAccess.PublicLink -> {
                if (AppConfig.DRIVE_API_KEY.isEmpty()) throw DriveException.ApiKeyMissing()
                builder.url(url.newBuilder().addQueryParameter("key", AppConfig.DRIVE_API_KEY).build())
                // Lets the key be restricted to this app in Cloud Console.
                packageName?.let { builder.header("X-Android-Package", it) }
                certSha1?.let { builder.header("X-Android-Cert", it) }
            }
        }
        return builder.get().build()
    }

    /**
     * Turns a failed read into an error that says which limit or permission
     * was hit, using the `reason` Drive puts in the error body.
     */
    private fun throwIfReadFailed(status: Int, body: String?, access: ReadAccess) {
        if (status in 200..299) return
        val error = body?.let { parse(it) }?.get("error")?.jsonObject
        val reason = runCatching {
            error?.get("errors")?.jsonArray?.firstOrNull()?.jsonObject?.get("reason")?.jsonPrimitive?.content
        }.getOrNull() ?: runCatching { error?.get("status")?.jsonPrimitive?.content }.getOrNull()

        if (status == 429 || reason in setOf(
                "userRateLimitExceeded", "rateLimitExceeded", "downloadQuotaExceeded", "dailyLimitExceeded"
            )
        ) {
            throw DriveException.RateLimited(reason ?: "HTTP $status")
        }
        val isPublic = access == ReadAccess.PublicLink
        if (isPublic && (status == 404 || reason == "notFound")) throw DriveException.NotPublic()
        // A bad key is a 400 `keyInvalid`; a key whose app restriction
        // doesn't match is a 403 `forbidden`.
        if (isPublic && (reason == "keyInvalid" || (status == 403 && reason == "forbidden"))) {
            throw DriveException.ApiKeyRejected()
        }
        throw DriveException.ReadFailed(status, reason)
    }

    private fun parseFile(obj: JsonObject): DriveFile? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        return DriveFile(
            id = id,
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: "",
            size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull(),
            createdTimeMs = obj["createdTime"]?.jsonPrimitive?.content
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        )
    }

    /** Lists files matching a Drive search query, newest first. */
    suspend fun listFiles(query: String, access: ReadAccess): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        repeat(MAX_LIST_PAGES) {
            val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,createdTime)")
                .addQueryParameter("orderBy", "createdTime desc")
                .addQueryParameter("pageSize", "200")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            execute(readRequest(url, access)).use { response ->
                val text = response.body?.string() ?: ""
                throwIfReadFailed(response.code, text, access)
                val page = parse(text) ?: throw IOException("Bad listing response")
                page["files"]?.jsonArray?.mapNotNullTo(files) { parseFile(it.jsonObject) }
                pageToken = page["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
            }
            if (pageToken == null) return files
        }
        return files
    }

    suspend fun listFilesInFolder(folderId: String, access: ReadAccess): List<DriveFile> =
        listFiles("'$folderId' in parents and trashed = false", access)

    /** A single file or folder's metadata, e.g. a shared folder's name. */
    suspend fun getFile(fileId: String, access: ReadAccess): DriveFile {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id,name,mimeType,size,createdTime")
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        execute(readRequest(url, access)).use { response ->
            val text = response.body?.string() ?: ""
            throwIfReadFailed(response.code, text, access)
            return parse(text)?.let { parseFile(it) } ?: throw IOException("Bad file response")
        }
    }

    private fun mediaUrl(fileId: String): HttpUrl =
        "https://www.googleapis.com/drive/v3/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .addQueryParameter("supportsAllDrives", "true")
            .build()

    suspend fun downloadText(fileId: String, access: ReadAccess): String {
        execute(readRequest(mediaUrl(fileId), access)).use { response ->
            val text = response.body?.string() ?: ""
            throwIfReadFailed(response.code, text, access)
            return text
        }
    }

    /** Streams a file straight to disk at [destination], replacing anything there. */
    suspend fun downloadFile(fileId: String, destination: File, access: ReadAccess) {
        execute(readRequest(mediaUrl(fileId), access)).use { response ->
            if (!response.isSuccessful) {
                throwIfReadFailed(response.code, response.body?.string(), access)
            }
            val body = response.body ?: throw IOException("Empty download")
            withContext(Dispatchers.IO) {
                val partial = File(destination.path + ".partial")
                partial.outputStream().use { out -> body.byteStream().copyTo(out) }
                destination.delete()
                if (!partial.renameTo(destination)) throw IOException("Couldn't save ${destination.name}")
            }
        }
    }

    /**
     * Pulls a folder id out of the links Drive hands out, e.g.
     * `https://drive.google.com/drive/folders/<id>?usp=sharing`,
     * `.../drive/u/0/folders/<id>` or `.../open?id=<id>`. A bare id is
     * accepted as-is.
     */
    fun folderIdFromLink(text: String): String? {
        val trimmed = text.trim()
        fun isId(candidate: String) =
            candidate.length >= 10 && candidate.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (isId(trimmed)) return trimmed

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.endsWith("google.com")) return null
        val parts = uri.pathSegments
        val index = parts.indexOf("folders")
        if (index >= 0 && index + 1 < parts.size && isId(parts[index + 1])) return parts[index + 1]
        val id = runCatching { uri.getQueryParameter("id") }.getOrNull()
        return id?.takeIf { isId(it) }
    }
}
