# Just Capture (Android)

Android version of the iOS app Just Capture: continuous video recording that
backs every clip up to your Google Drive as it's recorded, and emails the
people you choose a link to watch.

Package: `fyi.justfreesoftware.justcapture`

## Features

- **Record** — gapless chunked recording (10/30/60 s clips) on Camera2 with
  one continuous H.264 + AAC encoder; clips are cut on keyframes into
  standalone MP4s, so no footage is lost between them. "Send clip now" ends
  the current clip early. Torch, camera flip, readiness pills (contacts,
  Drive space, phone space, location), and banners for anything that would
  stop footage from reaching your contacts. The screen stays on while
  recording, and recording continues while you use the other tabs.
- **Uploads** — each clip uploads to a per-session Drive folder (resumable,
  two at a time, retried with backoff). The folder is shared as "anyone with
  the link", and a `playlist.m3u8` lists the clips in order. If Drive fills
  up, clips wait on the phone and upload once space is freed.
- **Alert emails** — sent through Gmail when recording starts, with the
  start time, address, map link and Drive link. Queued on disk and retried;
  if the folder link wasn't ready, a follow-up email sends it. A second
  email goes out if Drive fills up mid-recording.
- **My Clips** — sessions with upload status, a clip grid with thumbnails,
  retry, open in Drive, play the whole session back from Drive, and the
  alert email outbox.
- **Watch** — paste a Drive folder link someone shared with you to download
  and play it back to back; saved folders show when new clips have arrived.
- **Settings** — clip length, recipients (with a test email), email subject
  and message (`[your_name]` is replaced with your name), Drive and phone
  storage.

Storage layout (app-private files): `recordings/<sessionId>/chunk_N.mp4`,
`sessions.json`, `email_jobs.json`, `shared-folders.json`, and downloaded
shared folders under `SharedFolders/`.

## Build & run

Requires the Android SDK and JDK 17+ (Android Studio's bundled JBR works):

```
cd android
./gradlew assembleDebug
./gradlew installDebug
```

The recorder has an instrumented test that records on the device's camera
(an emulator with a virtual-scene camera works) and checks every clip:

```
./gradlew connectedDebugAndroidTest
```

## Google Cloud setup

- **Sign-in** reuses the iOS OAuth client ID in `config/AppConfig.kt`, with
  the redirect URI `com.googleusercontent.apps.<client-id>:/oauthredirect`
  handled by `AuthRedirectActivity`. If Google rejects that redirect from an
  Android install, create an additional OAuth client in the same project and
  update `GOOGLE_CLIENT_ID`/`REDIRECT_URI` plus the manifest's redirect scheme.
- **Watch tab** reads shared folders anonymously with the API key in
  `AppConfig.DRIVE_API_KEY`. Requests send `X-Android-Package` and
  `X-Android-Cert`, so if the key is restricted to apps, add an Android
  restriction for `fyi.justfreesoftware.justcapture` with your signing
  certificate's SHA-1 (debug and release), or the Watch tab reports that
  Google rejected the key.
