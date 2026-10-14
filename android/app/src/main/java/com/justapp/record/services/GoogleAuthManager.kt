package com.justapp.record.services

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.justapp.record.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class NotSignedInException : Exception("Not signed in")

/**
 * Google OAuth 2.0 sign-in using Chrome Custom Tabs + PKCE, with refresh-token
 * based session restore (mirrors the iOS app's GIDSignIn behavior). Tokens are
 * stored in private SharedPreferences.
 */
class GoogleAuthManager(
    context: Context,
    private val scope: CoroutineScope
) {
    val isSignedIn = MutableStateFlow(false)
    val isAuthenticating = MutableStateFlow(false)
    val userName = MutableStateFlow<String?>(null)
    val userEmail = MutableStateFlow<String?>(null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences =
        context.getSharedPreferences("just_auth", Context.MODE_PRIVATE)

    private val tokenMutex = Mutex()

    private class PendingAuth(val state: String, val codeVerifier: String)

    @Volatile
    private var pendingAuth: PendingAuth? = null

    /**
     * Sign in state survives process death as long as a refresh token is on
     * disk. If we're offline at launch we still consider the user signed in;
     * network calls will surface errors and retry on their own schedules.
     */
    suspend fun restoreSignIn() {
        if (prefs.getString(KEY_REFRESH_TOKEN, null) == null) return
        isSignedIn.value = true
        try {
            val token = validAccessToken()
            fetchUserInfo(token)
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh sign-in at launch (offline?): ${e.message}")
        }
    }

    fun signIn(context: Context) {
        if (isAuthenticating.value) return
        isAuthenticating.value = true
        scope.launch {
            try {
                val verifier = randomUrlSafe(32)
                val state = randomUrlSafe(16)
                pendingAuth = PendingAuth(state, verifier)
                val challenge = base64UrlSha256(verifier)

                val uri = Uri.parse(AppConfig.AUTHORIZATION_ENDPOINT).buildUpon()
                    .appendQueryParameter("client_id", AppConfig.GOOGLE_CLIENT_ID)
                    .appendQueryParameter("redirect_uri", AppConfig.REDIRECT_URI)
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("scope", AppConfig.OAUTH_SCOPES.joinToString(" "))
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("access_type", "offline")
                    .appendQueryParameter("prompt", "consent")
                    .build()

                launchCustomTab(context, uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start sign-in", e)
                isAuthenticating.value = false
            }
        }
    }

    private fun launchCustomTab(context: Context, uri: Uri) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: Exception) {
                Log.e(TAG, "No browser available for sign-in", e2)
                isAuthenticating.value = false
            }
        }
    }

    /**
     * Called from AuthRedirectActivity with the redirect URI the browser
     * bounced back to. Exchanges the authorization code for tokens.
     */
    suspend fun handleRedirect(uri: Uri) {
        val pending = pendingAuth
        pendingAuth = null

        val error = uri.getQueryParameter("error")
        if (error != null) {
            Log.w(TAG, "Sign-in was rejected by the user or server: $error")
            isAuthenticating.value = false
            return
        }

        if (pending == null ||
            uri.getQueryParameter("state") != pending.state ||
            uri.getQueryParameter("code") == null
        ) {
            Log.w(TAG, "Redirect state mismatch; ignoring")
            isAuthenticating.value = false
            return
        }

        try {
            exchangeCode(uri.getQueryParameter("code")!!, pending.codeVerifier)
            isSignedIn.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
        } finally {
            isAuthenticating.value = false
        }
    }

    private suspend fun exchangeCode(code: String, codeVerifier: String) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", AppConfig.GOOGLE_CLIENT_ID)
            .add("redirect_uri", AppConfig.REDIRECT_URI)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url(AppConfig.TOKEN_ENDPOINT)
            .post(body)
            .build()

        val tokenJson = executeJson(request).second

        val accessToken = tokenJson["access_token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("No access_token in response")
        val expiresInMs = (tokenJson["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L) * 1000

        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + expiresInMs)
            .apply()
        tokenJson["refresh_token"]?.jsonPrimitive?.content?.let { refreshToken ->
            prefs.edit().putString(KEY_REFRESH_TOKEN, refreshToken).apply()
        }

        fetchUserInfo(accessToken)
    }

    suspend fun validAccessToken(): String = tokenMutex.withLock {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        if (access != null && System.currentTimeMillis() < expiry - 60_000) {
            return access
        }

        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
            ?: throw NotSignedInException()

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", AppConfig.GOOGLE_CLIENT_ID)
            .add("refresh_token", refresh)
            .build()

        val request = Request.Builder()
            .url(AppConfig.TOKEN_ENDPOINT)
            .post(body)
            .build()

        val (statusCode, tokenJson) = executeJson(request)

        if (statusCode in 400..499) {
            // The refresh token was revoked or expired: drop the session.
            Log.w(TAG, "Refresh token rejected ($statusCode); signing out")
            clearTokens()
            throw NotSignedInException()
        }

        val accessToken = tokenJson["access_token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("No access_token in refresh response")
        val expiresInMs = (tokenJson["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L) * 1000

        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + expiresInMs)
            .apply()

        if (userName.value == null || userEmail.value == null) {
            try {
                fetchUserInfo(accessToken)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh user info: ${e.message}")
            }
        }

        accessToken
    }

    fun signOut() {
        clearTokens()
        isSignedIn.value = false
        userName.value = null
        userEmail.value = null
    }

    private fun clearTokens() {
        prefs.edit().clear().apply()
    }

    private suspend fun fetchUserInfo(accessToken: String) {
        val request = Request.Builder()
            .url(AppConfig.USER_INFO_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val (_, userJson) = executeJson(request)
        userEmail.value = userJson["email"]?.jsonPrimitive?.content
        userName.value = userJson["name"]?.jsonPrimitive?.content
    }

    private suspend fun executeJson(request: Request): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        response.use { resp ->
            val text = resp.body?.string() ?: ""
            val parsed = if (text.isNotEmpty()) {
                try {
                    json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    kotlinx.serialization.json.buildJsonObject { }
                }
            } else {
                kotlinx.serialization.json.buildJsonObject { }
            }
            return Pair(resp.code, parsed)
        }
    }

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun base64UrlSha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private companion object {
        const val TAG = "GoogleAuthManager"
        const val KEY_ACCESS_TOKEN = "accessToken"
        const val KEY_REFRESH_TOKEN = "refreshToken"
        const val KEY_TOKEN_EXPIRY = "tokenExpiry"
    }
}
