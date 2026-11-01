package fyi.justfreesoftware.justcapture.services

import android.util.Base64
import fyi.justfreesoftware.justcapture.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sends the emergency alert email through the Gmail API (messages/send with a
 * raw MIME message), mirroring the iOS app's GmailService.
 */
object GmailService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sendEmergencyEmail(
        to: List<String>,
        subject: String,
        bodyNote: String,
        includeTime: Boolean = true,
        address: String?,
        latitude: Double?,
        longitude: Double?,
        driveFolderLink: String?,
        accessToken: String
    ) {
        val html = buildEmailHtml(
            subject = subject,
            includeTime = includeTime,
            address = address,
            latitude = latitude,
            longitude = longitude,
            driveFolderLink = driveFolderLink,
            bodyNote = bodyNote
        )

        val htmlBase64 = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.DEFAULT).trim()
        val toLine = to.joinToString(", ")
        val subjectBase64 = Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val encodedSubject = "=?utf-8?B?$subjectBase64?="

        val mime = buildString {
            append("From: me\n")
            append("To: $toLine\n")
            append("Subject: $encodedSubject\n")
            append("MIME-Version: 1.0\n")
            append("Content-Type: text/html; charset=utf-8\n")
            append("Content-Transfer-Encoding: base64\n")
            append("X-Priority: 1\n")
            append("Importance: high\n")
            append("\n")
            append(htmlBase64)
        }.replace("\n", "\r\n")

        val base64Url = Base64.encodeToString(
            mime.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        val rawJson = buildJsonObject { put("raw", base64Url) }

        val request = Request.Builder()
            .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .post(rawJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        response.use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("Gmail send failed: ${resp.code} $text")
            }
        }
    }

    private fun buildEmailHtml(
        subject: String,
        includeTime: Boolean,
        address: String?,
        latitude: Double?,
        longitude: Double?,
        driveFolderLink: String?,
        bodyNote: String
    ): String {
        var template = loadTemplate()

        template = template.replace("{{SUBJECT}}", subject)

        if (includeTime) {
            val timeString = Formatters.formatEmailTimestamp(System.currentTimeMillis())
            val timeHtml =
                "<p style=\"color: #cccccc; font-size: 14px; margin-bottom: 20px;\"><strong>Recording Started:</strong> $timeString</p>"
            template = template.replace("{{TIMESTAMP_SECTION}}", timeHtml)
            template = template.replace("{{TIMESTAMP}}", timeString)
        } else {
            template = template.replace("{{TIMESTAMP_SECTION}}", "")
            template = template.replace("{{TIMESTAMP}}", "")
        }

        if (!address.isNullOrEmpty()) {
            val addressHtml =
                "<p style=\"color: #e0e0e0; font-size: 14px; margin: 10px 0;\">📍 <strong>Location:</strong> $address</p>"
            template = template.replace("{{ADDRESS_SECTION}}", addressHtml)
        } else {
            template = template.replace("{{ADDRESS_SECTION}}", "")
        }

        if (latitude != null && longitude != null) {
            val mapHtml = """
                <p style="margin: 10px 0;">
                    <a href="https://www.google.com/maps?q=$latitude,$longitude" style="color: #4da6ff; text-decoration: none; font-weight: 500;">
                        📍 View location on Google Maps →
                    </a>
                </p>
            """.trimIndent()
            template = template.replace("{{MAP_LINK_SECTION}}", mapHtml)
        } else {
            template = template.replace("{{MAP_LINK_SECTION}}", "")
        }

        if (!driveFolderLink.isNullOrEmpty()) {
            val driveHtml = """
                <p style="margin: 20px 0;">
                    <a href="$driveFolderLink" style="display: inline-block; padding: 12px 24px; background-color: #007bff; color: #ffffff; text-decoration: none; border-radius: 8px; font-weight: bold; font-size: 14px;">
                        View Live Recording on Google Drive
                    </a>
                </p>
            """.trimIndent()
            template = template.replace("{{DRIVE_LINK_SECTION}}", driveHtml)
        } else {
            template = template.replace("{{DRIVE_LINK_SECTION}}", "")
        }

        if (bodyNote.isNotEmpty()) {
            val noteHtml = """
                <p style="background-color: #2a2a2a; border-left: 4px solid #ff4444; padding: 12px; border-radius: 4px; color: #e0e0e0; font-size: 14px; margin: 15px 0;">
                    📝 <strong>Note:</strong> $bodyNote
                </p>
            """.trimIndent()
            template = template.replace("{{NOTE_SECTION}}", noteHtml)
        } else {
            template = template.replace("{{NOTE_SECTION}}", "")
        }

        return template
    }

    private fun loadTemplate(): String {
        // The template is embedded (the bundle-file lookup on iOS has an
        // identical fallback inline).
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>{{SUBJECT}}</title>
        </head>
        <body style="margin: 0; padding: 0; background-color: #121212; color: #ffffff; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;">
            <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background-color: #121212; padding: 30px 15px;">
                <tr>
                    <td align="center">
                        <table role="presentation" width="100%" style="max-width: 600px; background-color: #1e1e1e; border-radius: 12px; padding: 24px; box-shadow: 0 4px 12px rgba(0,0,0,0.5);" cellspacing="0" cellpadding="0" border="0">
                            <tr>
                                <td>
                                    <h2 style="color: #ff4444; margin: 0 0 20px 0; font-size: 22px; font-weight:600">
                                        {{SUBJECT}}
                                    </h2>

                                    {{TIMESTAMP_SECTION}}

                                    {{ADDRESS_SECTION}}

                                    {{MAP_LINK_SECTION}}

                                    {{DRIVE_LINK_SECTION}}

                                    {{NOTE_SECTION}}

                                    <hr style="border: 0; border-top: 1px solid #333333; margin: 25px 0 15px 0;">

                                </td>
                            </tr>
                        </table>
                    </td>
                </tr>
            </table>
        </body>
        </html>
        """
    }
}
