package com.justapp.record.auth

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import com.justapp.record.JustApplication
import kotlinx.coroutines.launch

/**
 * Receives the OAuth redirect after Google sign-in completes in a browser
 * tab. Forwards the URI to the auth manager and finishes immediately; the
 * main UI reacts to the auth state change.
 */
class AuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val app = application as? JustApplication
        if (uri != null && app != null) {
            app.appScope.launch {
                app.graph.authManager.handleRedirect(uri)
            }
        }
        finish()
    }
}
