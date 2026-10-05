# ClawHark

ClawHark records audio on a Wear OS watch and uploads completed recordings directly to your Google Drive. It runs on the watch without a companion phone app.

## Recording and uploads

- Always-on microphone recording with a persistent notification and a partial wake lock; capture continues until you stop it, subject to microphone availability, permissions, and storage.
- Amplitude-based silence filtering; loud background noise can also pass the filter.
- Up to 15-minute AAC/M4A chunks at 16 kHz mono and 32 kbps.
- Google Drive uploads to a `ClawHark` folder using the limited `drive.file` scope.
- Uploads scheduled hourly on an unmetered connection, with a four-hour fallback on any connected network. WorkManager may defer execution.
- Resumable uploads through Google's Drive client. Persistent file IDs prevent duplicate uploads after a lost acknowledgement.
- Local recordings deleted only after Drive confirms the file's ID, name, size, and checksum.
- Microphone recovery retries automatically. Storage pressure pauses capture and preserves pending audio.
- After reboot, the app attempts to resume recording if you left it enabled. If Android rejects the background start or microphone access, a notification asks you to open the app.

The project contains the watch app and Google Drive upload functionality. There is no desktop sync, transcription, AI pipeline, or HTTP recording server.

## Build

Requirements: JDK 17, an Android SDK with platform 34, and a Wear OS watch running Android API 30 or newer.

Enable the Google Drive API in your [Google Cloud project](https://console.cloud.google.com/apis/library/drive.googleapis.com). Create an OAuth client with type **TVs and Limited Input devices** in [Google Cloud Console](https://console.cloud.google.com/apis/credentials), and configure the OAuth consent screen for the `https://www.googleapis.com/auth/drive.file` scope.

Copy `app/src/main/assets/oauth_config.json.example` to `app/src/main/assets/oauth_config.json` and fill in your client ID and client secret. This local configuration is ignored by Git. OAuth client credentials bundled in an APK are extractable; account access and refresh tokens are kept in encrypted on-device preferences.

Set `JAVA_HOME` to your JDK 17 installation and `ANDROID_HOME` to your SDK installation, or configure `sdk.dir` in ignored `local.properties`.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew assembleRelease` builds a minimized unsigned APK when signing properties are absent. To produce an installable signed release, provide your own signing configuration using `keystore.properties.example` as a template.

## Install and use

Enable wireless debugging on the watch, pair and connect with [ADB](https://developer.android.com/tools/adb), then install:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open ClawHark, grant microphone permission, tap **LINK**, and enter the displayed code at Google's device authorization page. Linking enables recording while the app is visible.

- Tap **START** to record.
- Tap **STOP**, then confirm within three seconds to stop. The final chunk is completed before the final upload is scheduled.
- Long-press the recording button to sign out. This stops recording, disconnects locally, cancels queued uploads, and attempts to revoke Google's authorization.
- If revocation cannot complete, revoke access through [Google Account permissions](https://myaccount.google.com/permissions).
- Open your `ClawHark` folder in Google Drive to play or download recordings.

Pending audio remains on the watch when you stop recording or sign out. Signing in again uploads the pending queue to the account you link. Uninstalling deletes local recordings and credentials.

If Google authorization is revoked during recording, audio stays local and the recording controls remain available. Stop recording and link Drive again to resume uploads.

Notification permission is needed for resume reminders, but microphone permission alone is sufficient to record. If automatic restart fails and notifications are disabled, open ClawHark manually after restarting the watch.

On first launch, the app opens Android's battery optimization settings. Allow ClawHark there if you need recording to continue while the watch is stationary with its screen off. Doze can otherwise suspend CPU work and defer uploads.

## Reliability and limitations

The UI distinguishes recording, microphone recovery, and storage pauses. Pending audio is preserved at the 500 MB local queue limit or when available space falls below 50 MB. Uploads can free space and allow recording to resume.

Automatic recording after reboot still needs testing on the Pixel Watch 3 running Wear OS 7. Android documents [restrictions and exceptions for microphone foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone); a successful service-start request alone does not prove microphone capture resumed.

Incomplete files left by a crash are kept locally. Only temporary files that contain a readable audio track are published for upload. An abrupt process termination can leave the current MP4 chunk unplayable; this cannot be repaired by renaming it.

Continuous microphone capture and its wake lock consume battery even during silence. Battery life and real microphone/codec behavior must be checked on the intended watch.

## Debugging

Debug builds support:

```bash
adb logcat -s WR.Service WR.Drive WR.Auth WR.Upload
adb shell "run-as ai.etti.clawhark cat files/logs/clawhark.log"
adb shell "run-as ai.etti.clawhark ls -la files/recordings"
```

Regression tests exercise service lifecycle, permission gates, upload ownership, and HTTP failures with Robolectric and MockWebServer. These checks supplement testing on a physical watch.

## Privacy and license

See [PRIVACY.md](PRIVACY.md) for storage, transfer, and account controls. MIT licensed; see [LICENSE](LICENSE).
