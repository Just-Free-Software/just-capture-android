package fyi.justfreesoftware.justcapture.services

import android.content.Context
import android.util.Base64
import android.util.Log
import fyi.justfreesoftware.justcapture.config.AppConfig
import fyi.justfreesoftware.justcapture.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends the alert email through the Gmail API (messages/send with a raw MIME
 * message), using assets/email_template.html.
 */
object GmailService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private var template: String? = null

    fun init(context: Context) {
        template = runCatching {
            context.assets.open("email_template.html").bufferedReader().use { it.readText() }
        }.onFailure { Log.w("GmailService", "Couldn't load email template", it) }.getOrNull()
    }

    suspend fun sendEmergencyEmail(
        to: List<String>,
        subject: String,
        bodyNote: String,
        startedAtMs: Long?,
        address: String?,
        latitude: Double?,
        longitude: Double?,
        driveFolderLink: String?,
        accessToken: String
    ) {
        val html = buildEmailHtml(subject, startedAtMs, address, latitude, longitude, driveFolderLink, bodyNote)

        val htmlBase64 = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.DEFAULT).trim()
        // Strip CR/LF so user-entered values can't break out of their header.
        val toLine = to.joinToString(", ") { it.replace(Regex("[\\r\\n]"), "") }
        val subjectBase64 = Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val mime = buildString {
            append("From: me\n")
            append("To: $toLine\n")
            append("Subject: =?utf-8?B?$subjectBase64?=\n")
            append("MIME-Version: 1.0\n")
            append("Content-Type: text/html; charset=utf-8\n")
            append("Content-Transfer-Encoding: base64\n")
            append("X-Priority: 1\n")
            append("Importance: high\n")
            append("\n")
            append(htmlBase64)
        }.replace("\n", "\r\n")

        val raw = Base64.encodeToString(
            mime.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        val request = Request.Builder()
            .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
            .header("Authorization", "Bearer $accessToken")
            .post(buildJsonObject { put("raw", raw) }.toString().toRequestBody("application/json".toMediaType()))
            .build()

        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("GmailService", "Gmail API error ${resp.code}: ${resp.body?.string()}")
                    throw IOException("Gmail send failed: ${resp.code}")
                }
            }
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("\n", "<br>")

    private fun buildEmailHtml(
        subject: String,
        startedAtMs: Long?,
        address: String?,
        latitude: Double?,
        longitude: Double?,
        driveFolderLink: String?,
        bodyNote: String
    ): String {
        var html = template ?: FALLBACK_TEMPLATE
        html = html.replace("{{SUBJECT}}", escapeHtml(subject))

        // The time the recording started (when the job was queued), not the
        // time this particular send attempt happens to succeed.
        if (startedAtMs != null) {
            val time = Formatters.emailTimestamp(startedAtMs)
            html = html.replace("{{TIMESTAMP_SECTION}}", "<p>Recording started: $time</p>")
                .replace("{{TIMESTAMP}}", time)
        } else {
            html = html.replace("{{TIMESTAMP_SECTION}}", "").replace("{{TIMESTAMP}}", "")
        }

        html = html.replace(
            "{{ADDRESS_SECTION}}",
            if (!address.isNullOrEmpty()) "<p>Location: ${escapeHtml(address)}</p>" else ""
        )

        html = html.replace(
            "{{MAP_LINK_SECTION}}",
            if (latitude != null && longitude != null) {
                "<p><a href=\"https://www.google.com/maps?q=$latitude,$longitude\">View location on Google Maps</a></p>"
            } else ""
        )

        html = html.replace(
            "{{DRIVE_LINK_SECTION}}",
            if (!driveFolderLink.isNullOrEmpty()) {
                "<p><a href=\"${escapeHtml(driveFolderLink)}\">View the live recording on Google Drive</a></p>"
            } else ""
        )

        html = html.replace(
            "{{NOTE_SECTION}}",
            if (bodyNote.isNotEmpty()) "<p>Note: ${escapeHtml(bodyNote)}</p>" else ""
        )

        html = html.replace(
            "{{APP_LINK_SECTION}}",
            "<p style=\"color: #888888; font-size: 12px;\">For a better viewing experience, " +
                "<a href=\"${escapeHtml(AppConfig.HOMEPAGE_URL)}\">download Just Capture</a>.</p>"
        )
        return html
    }

    private const val FALLBACK_TEMPLATE = """<!DOCTYPE html>
<html>
<body style="font-family: sans-serif; font-size: 14px;">
    <p><strong>{{SUBJECT}}</strong></p>
    {{TIMESTAMP_SECTION}}
    {{ADDRESS_SECTION}}
    {{MAP_LINK_SECTION}}
    {{DRIVE_LINK_SECTION}}
    {{NOTE_SECTION}}
    {{APP_LINK_SECTION}}
</body>
</html>"""
}
