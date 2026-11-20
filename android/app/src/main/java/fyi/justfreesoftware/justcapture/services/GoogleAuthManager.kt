package fyi.justfreesoftware.justcapture.services

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import fyi.justfreesoftware.justcapture.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NotSignedInException : Exception("Not signed in")

/**
 * Google sign-in through Google Play services' AuthorizationClient.
 *
 * Play services identifies the app by its package name and signing
 * certificate, which must match an Android OAuth client in the Cloud project.
 * It also keeps the grant and refreshes access tokens itself, so the app
 * stores only the signed-in account's email, and background work can get a
 * token without a refresh token or a server.
 *
 * This replaced a browser sign-in that borrowed the iOS OAuth client. Google
 * expects each platform to use its own client type, and blocks custom-scheme
 * redirects for Android clients, so that flow couldn't be kept.
 */
class GoogleAuthManager(
    context: Context,
    private val scope: CoroutineScope
) {
    val isSignedIn = MutableStateFlow(false)
    val isAuthenticating = MutableStateFlow(false)
    val userEmail = MutableStateFlow<String?>(null)
    /** Google profile name; null if the user didn't grant profile access. */
    val userName = MutableStateFlow<String?>(null)

    /** Shown to the user when sign-in fails for a reason other than cancelling. */
    val signInError = MutableStateFlow<String?>(null)

    private val authClient = Identity.getAuthorizationClient(context.applicationContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences =
        context.getSharedPreferences("just_capture_auth", Context.MODE_PRIVATE)

    private val tokenMutex = Mutex()

    private val scopes = AppConfig.OAUTH_SCOPES.map { Scope(it) }

    /**
     * Sign-in state survives process death as long as the account is saved.
     * If we're offline at launch we still consider the user signed in;
     * network calls will surface errors and retry on their own schedules.
     */
    suspend fun restoreSignIn() {
        // Builds before the switch to Play services saved a refresh token for
        // the iOS client. It can't be used with this flow, so those users
        // sign in once more.
        if (prefs.contains(LEGACY_KEY_REFRESH_TOKEN)) {
            Log.i(TAG, "Dropping a sign-in from the old browser flow")
            prefs.edit().clear().apply()
            return
        }
        val email = prefs.getString(KEY_ACCOUNT_EMAIL, null) ?: return
        userEmail.value = email
        userName.value = prefs.getString(KEY_ACCOUNT_NAME, null)
        isSignedIn.value = true
        try {
            validAccessToken()
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh sign-in at launch (offline?): ${e.message}")
        }
    }

    /**
     * Starts sign-in. If Play services needs the user to pick an account or
     * grant access, [showScreen] is called with its screen, and the result comes
     * back through [handleSignInResult].
     */
    fun signIn(showScreen: (IntentSenderRequest) -> Unit) {
        if (isAuthenticating.value) return
        isAuthenticating.value = true
        signInError.value = null
        scope.launch {
            try {
                val result = authClient.authorize(request(account = null)).await()
                val pendingIntent = result.pendingIntent
                if (result.hasResolution() && pendingIntent != null) {
                    // Stays "authenticating" until the screen reports back.
                    showScreen(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    return@launch
                }
                // Access was already granted on this phone.
                finishSignIn(result)
            } catch (e: Exception) {
                reportSignInFailure(e)
            }
            isAuthenticating.value = false
        }
    }

    /** Called with the result of the screen [signIn] launched. */
    suspend fun handleSignInResult(resultCode: Int, data: Intent?) {
        try {
            // Backing out of the account picker isn't worth an alert.
            if (resultCode != Activity.RESULT_OK || data == null) return
            finishSignIn(authClient.getAuthorizationResultFromIntent(data))
        } catch (e: Exception) {
            reportSignInFailure(e)
        } finally {
            isAuthenticating.value = false
        }
    }

    private suspend fun finishSignIn(result: AuthorizationResult) {
        val token = result.accessToken ?: throw IllegalStateException("No access token in authorization result")
        // The consent screen can let people untick a permission. Without
        // Drive and Gmail the app can't do its job, so don't half sign in.
        val missing = AppConfig.REQUIRED_SCOPES - result.grantedScopes.toSet()
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Sign-in is missing scopes: $missing")
            signInError.value = "Just Capture needs permission to save to Google Drive and send email from Gmail. " +
                "Sign in again and allow both."
            return
        }
        val info = fetchUserInfo(token)
        val email = info["email"] ?: throw IllegalStateException("No email in user info")
        val name = info["name"]?.trim()?.takeIf { it.isNotEmpty() }
        val granted = result.grantedScopes.toSet().intersect(AppConfig.OAUTH_SCOPES.toSet())
        prefs.edit()
            .putString(KEY_ACCOUNT_EMAIL, email)
            .putString(KEY_ACCOUNT_NAME, name)
            .putStringSet(KEY_GRANTED_SCOPES, granted)
            .apply()
        userEmail.value = email
        userName.value = name
        isSignedIn.value = true
    }

    private fun reportSignInFailure(e: Exception) {
        val status = (e as? ApiException)?.statusCode
        when (status) {
            CommonStatusCodes.CANCELED -> return
            CommonStatusCodes.DEVELOPER_ERROR -> Log.e(
                TAG,
                "Google rejected the app's identity. The Cloud project needs an Android OAuth client " +
                    "for this package name and the SHA-1 of the key this build is signed with.",
                e
            )
            else -> Log.e(TAG, "Sign-in failed", e)
        }
        signInError.value = if (status == CommonStatusCodes.NETWORK_ERROR) {
            "Couldn't reach Google. Check your connection and try again."
        } else {
            "Google sign-in failed. Try again."
        }
    }

    /**
     * Play services returns its cached token, or refreshes it when it's
     * expired, so this is normally a local call. Callers retry on their own
     * backoff schedules, which bounds how often a failing refresh reaches
     * Google.
     */
    suspend fun validAccessToken(): String = tokenMutex.withLock {
        val email = prefs.getString(KEY_ACCOUNT_EMAIL, null) ?: throw NotSignedInException()
        val result = authClient.authorize(request(Account(email, GOOGLE_ACCOUNT_TYPE), tokenScopes())).await()
        if (result.hasResolution()) {
            // The user revoked access, or the account left the phone. Only
            // the sign-in screen can fix that, and background work can't
            // show it.
            Log.w(TAG, "Authorization needs the user again; signing out")
            signOut()
            throw NotSignedInException()
        }
        result.accessToken ?: throw IllegalStateException("No access token in authorization result")
    }

    fun signOut() {
        val email = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        prefs.edit().clear().apply()
        isSignedIn.value = false
        userEmail.value = null
        userName.value = null
        // Without revoking, Play services keeps the grant and the next
        // sign-in would reuse this account without letting the user choose.
        if (email != null) {
            val request = RevokeAccessRequest.builder()
                .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
                .setScopes(scopes)
                .build()
            authClient.revokeAccess(request)
                .addOnFailureListener { Log.w(TAG, "Could not revoke access on sign-out: ${it.message}") }
        }
    }

    /**
     * Token refreshes ask only for what the user granted. Asking for more
     * (a scope they unticked, or one added after they signed in) makes Play
     * services want the consent screen, which [validAccessToken] treats as
     * signed out, possibly mid-recording. Sign-ins saved before scopes were
     * stored predate the profile scope.
     */
    private fun tokenScopes(): List<Scope> {
        val granted = prefs.getStringSet(KEY_GRANTED_SCOPES, null)
            ?: (AppConfig.OAUTH_SCOPES - AppConfig.PROFILE_SCOPE).toSet()
        return granted.map { Scope(it) }
    }

    private fun request(account: Account?, requested: List<Scope> = scopes): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(requested)
        if (account != null) builder.setAccount(account)
        return builder.build()
    }

    /** The user info fields this app reads: `email`, and `name` with profile access. */
    private suspend fun fetchUserInfo(accessToken: String): Map<String, String> {
        val request = Request.Builder()
            .url(AppConfig.USER_INFO_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val text = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("User info request failed (${resp.code})")
                resp.body?.string() ?: ""
            }
        }
        val obj = json.parseToJsonElement(text).jsonObject
        return listOf("email", "name").mapNotNull { key -> obj[key]?.jsonPrimitive?.content?.let { key to it } }.toMap()
    }

    private companion object {
        const val TAG = "GoogleAuthManager"
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val KEY_ACCOUNT_EMAIL = "accountEmail"
        const val KEY_ACCOUNT_NAME = "accountName"
        const val KEY_GRANTED_SCOPES = "grantedScopes"
        const val LEGACY_KEY_REFRESH_TOKEN = "refreshToken"
    }
}
