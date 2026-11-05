package fyi.justfreesoftware.justcapture.config

object AppConfig {
    const val GOOGLE_CLIENT_ID = "1003597781614-l45439l3m6is4lcn2bmhhhj67292inmg.apps.googleusercontent.com"
    const val REDIRECT_URI = "com.googleusercontent.apps.1003597781614-l45439l3m6is4lcn2bmhhhj67292inmg:/oauthredirect"

    val OAUTH_SCOPES = listOf(
        "https://www.googleapis.com/auth/drive.file",
        "https://www.googleapis.com/auth/gmail.send",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile"
    )

    /**
     * Google Cloud API key used to read folders other people shared as
     * "anyone with the link". Restrict it to the Drive API and this app's
     * package + signing certificate in Cloud Console; it ships in the app,
     * so treat it as public.
     */
    const val DRIVE_API_KEY = "AIzaSyBRjrEWpSUE80PWNgTGPUPFS-uw1WzYJag"

    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val USER_INFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo"

    const val DEFAULT_CHUNK_DURATION_SEC = 10.0
    const val MAX_CONCURRENT_UPLOADS = 2
    const val VIDEO_WIDTH = 1920
    const val VIDEO_HEIGHT = 1080

    // ~6 Mbps 1080p; used until real chunk data is available
    const val ESTIMATED_VIDEO_BYTES_PER_SEC = 750_000.0

    /** Linked from alert emails. Live once the app is listed on Google Play. */
    const val APP_DOWNLOAD_URL = "https://play.google.com/store/apps/details?id=fyi.justfreesoftware.justcapture"
}
