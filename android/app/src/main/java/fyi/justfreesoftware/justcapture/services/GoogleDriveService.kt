package fyi.justfreesoftware.justcapture.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit

class DriveStorageQuotaExceededException : Exception("Google Drive storage is full.")

data class DriveStorageQuota(val limit: Long?, val usage: Long) {
    val isUnlimited: Boolean
        get() = (limit ?: 0L) <= 0L

    val availableBytes: Long?
        get() = if (isUnlimited) null else maxOf(0L, (limit ?: 0L) - usage)
}

/**
 * Google Drive v3 REST calls: storage quota, folder creation, resumable file
 * uploads (with progress), and the session playlist file. Mirrors the iOS
 * app's GoogleDriveService.
 */
object GoogleDriveService {

    private const val UPLOAD_CHUNK_SIZE = 2 * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
    private val MP4_MEDIA_TYPE = "video/mp4".toMediaType()

    private suspend fun execute(request: Request): Response =
        withContext(Dispatchers.IO) { client.newCall(request).execute() }

    private fun throwIfQuotaExceeded(bodyText: String, statusCode: Int) {
        if (statusCode != 403) return
        val parsed = runCatching { json.parseToJsonElement(bodyText).jsonObject }
        val error = parsed.getOrNull()?.get("error")?.jsonObject ?: return
        val reasons = error["errors"]?.jsonArray ?: return
        for (item in reasons) {
            val reason = runCatching { item.jsonObject["reason"]?.jsonPrimitive?.content }.getOrNull()
            if (reason == "storageQuotaExceeded") throw DriveStorageQuotaExceededException()
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
            if (!response.isSuccessful) throw RuntimeException("Drive about failed: ${response.code}")

            val quotaJson = json.parseToJsonElement(text).jsonObject["storageQuota"]?.jsonObject
                ?: throw RuntimeException("No storageQuota in response")
            val usage = quotaJson["usage"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw RuntimeException("No usage in storageQuota")
            val limit = quotaJson["limit"]?.jsonPrimitive?.content?.toLongOrNull()
            return DriveStorageQuota(limit = limit, usage = usage)
        }
    }

    suspend fun createFolder(name: String, accessToken: String): Pair<String, String> {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
        }
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id,webViewLink")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        execute(request).use { response ->
            val text = response.body?.string() ?: ""
            throwIfQuotaExceeded(text, response.code)
            val parsed = json.parseToJsonElement(text).jsonObject
            val id = parsed["id"]?.jsonPrimitive?.content
                ?: throw RuntimeException("Folder creation failed: ${response.code} $text")
            val webViewLink = parsed["webViewLink"]?.jsonPrimitive?.content
                ?: throw RuntimeException("Folder created without webViewLink")
            return Pair(id, webViewLink)
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
            put("parents", kotlinx.serialization.json.buildJsonArray { add(folderId) })
        }
        val initiate = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", "video/mp4")
            .header("X-Upload-Content-Length", fileSize.toString())
            .header("Content-Type", "application/json; charset=UTF-8")
            .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val resumableUrl = execute(initiate).use { response ->
            val text = response.body?.string() ?: ""
            throwIfQuotaExceeded(text, response.code)
            response.header("Location")
                ?: throw RuntimeException("Resumable upload initiation failed: ${response.code} $text")
        }

        // 2. Push the file contents in 2 MB chunks, streaming from disk.
        var fileId: String? = null
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(UPLOAD_CHUNK_SIZE)
            var offset = 0L
            while (offset < fileSize || fileSize == 0L) {
                val chunkLength = if (fileSize == 0L) 0
                else minOf(UPLOAD_CHUNK_SIZE.toLong(), fileSize - offset).toInt()

                if (chunkLength > 0) {
                    raf.seek(offset)
                    raf.readFully(buffer, 0, chunkLength)
                }

                val builder = Request.Builder()
                    .url(resumableUrl)
                    .put((if (chunkLength > 0) buffer.copyOf(chunkLength) else ByteArray(0)).toRequestBody(MP4_MEDIA_TYPE))
                    .header("Content-Length", chunkLength.toString())

                if (fileSize > chunkLength && chunkLength > 0) {
                    builder.header(
                        "Content-Range",
                        "bytes $offset-${offset + chunkLength - 1}/$fileSize"
                    )
                }

                execute(builder.build()).use { response ->
                    val text = response.body?.string() ?: ""
                    when {
                        response.code == 308 -> {
                            offset += chunkLength
                            if (fileSize > 0) onProgress(offset.toDouble() / fileSize.toDouble())
                        }
                        response.isSuccessful -> {
                            fileId = json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content
                            onProgress(1.0)
                        }
                        else -> {
                            throwIfQuotaExceeded(text, response.code)
                            throw RuntimeException("Chunk upload failed: ${response.code} $text")
                        }
                    }
                }
                if (fileSize == 0L) break
            }
        }

        return fileId ?: throw RuntimeException("Upload finished without a file id")
    }

    suspend fun uploadOrUpdatePlaylist(
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
            put("mimeType", "application/x-mpegURL")
            if (!isUpdate) {
                put("name", "playlist.m3u8")
                put("parents", kotlinx.serialization.json.buildJsonArray { add(folderId) })
            }
        }

        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString())
            append("\r\n")
            append("--$boundary\r\n")
            append("Content-Type: application/x-mpegURL\r\n\r\n")
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
            val id = json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content
            return id ?: throw RuntimeException("Playlist upload failed: ${response.code} $text")
        }
    }

    fun getDirectDownloadUrl(fileId: String): String =
        "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
}
