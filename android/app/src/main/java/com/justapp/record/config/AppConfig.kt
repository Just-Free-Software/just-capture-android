package com.justapp.record.config

object AppConfig {
    const val GOOGLE_CLIENT_ID = "1003597781614-l45439l3m6is4lcn2bmhhhj67292inmg.apps.googleusercontent.com"
    const val REDIRECT_URI = "com.googleusercontent.apps.1003597781614-l45439l3m6is4lcn2bmhhhj67292inmg:/oauthredirect"

    val OAUTH_SCOPES = listOf(
        "https://www.googleapis.com/auth/drive.file",
        "https://www.googleapis.com/auth/gmail.send",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile"
    )

    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val USER_INFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo"

    const val DEFAULT_CHUNK_DURATION_SEC = 30.0

    // ~6 Mbps HEVC 1080p; used until real chunk data is available
    const val ESTIMATED_VIDEO_BYTES_PER_SEC = 750_000.0
}
