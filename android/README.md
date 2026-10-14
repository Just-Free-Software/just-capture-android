# Just (Android)

Native Android remake of the iOS "Just App 2" — continuous video recording that
uploads clips to Google Drive as they're recorded, with emergency alert emails.

## Feature parity with the iOS app

- **Sign in with Google** (OAuth 2.0 + PKCE via Chrome Custom Tab; scopes:
  `drive.file`, `gmail.send`, `userinfo.email`, `userinfo.profile`). Session is
  restored across launches via a refresh token.
- **Chunked continuous recording** (CameraX, 1080p, front/back camera, torch):
  a new clip is written every 10/30/60 s, plus a manual "cut chunk now" button.
- **Drive upload pipeline** per clip: resumable uploads (2 MB chunks) with
  exponential backoff, session folder creation, and an auto-refreshed
  `playlist.m3u8` (HLS) inside the session folder.
- **Quota handling**: a 403 `storageQuotaExceeded` parks the chunk, sends an
  alert email, and polls the (cheap) quota endpoint with growing backoff until
  space is freed; low-storage gating before a recording starts.
- **Emergency alert emails** through the Gmail API with an HTML template
  (timestamp, reverse-geocoded address, Google Maps link, Drive folder link,
  custom note). The outbox persists to disk and retries with backoff.
- **Dashboard**: per-session stats, chunk status cards with live upload
  progress, email outbox status, in-app local playback, delete session.
- **Settings**: chunk duration, email recipients/subject/notes, include
  time/location, Drive storage status, "Save Chunks Locally".

Storage layout (private app files, mirrors the iOS app's documents folder):
`recordings/<sessionId>/chunk_N.mp4`, `sessions.json`, `email_jobs.json`.

## Build & run

Requires Android Studio (Koala or newer) with JDK 17, or the Android SDK +
Gradle 8.9:

```
cd android
./gradlew assembleDebug
```

Then install on a device (camera + microphone + location are needed):

```
./gradlew installDebug
```

## Google OAuth setup

The app reuses the iOS client ID in
`config/AppConfig.kt`, with the iOS-style redirect URI
`com.googleusercontent.apps.<client-id>:/oauthredirect`, handled by
`AuthRedirectActivity` in the manifest.

If Google rejects that redirect from an Android install, create an additional
**Web application** OAuth client in Google Cloud Console (same project, same
scopes), add the redirect URI `com.justapp.record:/oauthredirect`, and update
`GOOGLE_CLIENT_ID`/`REDIRECT_URI` in `AppConfig.kt` plus the manifest's
redirect activity scheme. Everything else works unchanged.
