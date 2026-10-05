> Fork of [ivar2000/clawhark](https://github.com/ivar2000/clawhark). Original project copyright © 2026 Michael Ettlinger; see [LICENSE](LICENSE).

`ClawHark` records audio on a Wear OS watch and saves completed recordings to your Google Drive. This fork focuses on watch recording and Drive uploads, for people who want to capture audio without a companion phone app.

# What It Does

- **Always-on recording:** Keeps capturing until you stop it, subject to microphone availability, permissions, storage, and Android background restrictions.
- **Silence filtering:** Saves audio above an amplitude threshold, including loud background noise.
- **Portable recordings:** Creates mono AAC/M4A files in chunks of up to 15 minutes.
- **Google Drive uploads:** Sends completed files to a `ClawHark` folder in the account you link.
- **Queue preservation:** Retains pending audio through upload failures and pauses capture when storage is full.
- **Watch controls:** Start with one tap, confirm stopping with a second tap, and long-press to sign out.

# How It Works

1. Install a configured APK on your watch and grant microphone permission.
2. Tap **LINK** and enter the displayed code at Google's device authorization page.
3. Keep the watch charged while it records and filters silence. Linking starts recording when the app is visible.
4. Give the watch a network connection. Completed recordings appear in your `ClawHark` folder in Google Drive, where you can play or download them.

# How it Really Works

1. **Audio stays local until upload.** The watch stores recordings in private app storage at 16 kHz mono and 32 kbps. Silence filtering uses sound amplitude; it does not identify speakers or distinguish speech from other noise.
2. **Uploads follow network availability.** The app schedules uploads hourly on an unmetered connection, with a four-hour fallback on any connected network. Android can defer those jobs. Stopping recording also schedules an upload of the final completed chunk.
3. **Upload confirmation controls deletion.** Interrupted transfers are retried. A persistent file ID prevents duplicate uploads after a lost acknowledgement. The local file is deleted only after Drive confirms the matching ID, name, size, and checksum.
4. **Storage pressure pauses recording.** At a 500 MiB local queue or below 50 MiB of free space, capture pauses while preserving pending audio. Uploads can free space and allow recording to resume; microphone failures are retried automatically.
5. **Account changes preserve the local queue.** Signing out stops recording, clears local credentials, cancels uploads, and attempts to revoke Google's authorization. Linking another account sends the pending queue to that account. Uninstalling deletes local audio and credentials; files already in Drive remain there.

# Prerequisites

- A Wear OS watch running Android API 30 or newer, with a microphone.
- A Google account with available Drive storage and a network connection for authorization and uploads.
- An APK configured for Google's device authorization flow. Build and OAuth setup instructions are in [AGENTS.md](AGENTS.md).
- [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools), with `adb` available on your computer, for watch installation.

# Installation

Enable wireless debugging on the watch, then pair and connect using [ADB's wireless debugging instructions](https://developer.android.com/tools/adb#wireless-android11-command-line).

For the debug APK produced by the build instructions in [AGENTS.md](AGENTS.md):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A release APK must be signed before installation. Its signing setup is documented in [AGENTS.md](AGENTS.md).

# Quick Start

1. Open `ClawHark`, grant microphone permission, tap **LINK**, and authorize the displayed code with Google.
2. Tap **START** when stopped. To stop, tap **STOP**, then tap again within three seconds to confirm.
3. Open the `ClawHark` folder in Google Drive to check a recent recording containing speech.
4. To disconnect, long-press the recording button. If Google's revocation cannot complete, remove access through [Google Account permissions](https://myaccount.google.com/permissions).

# Configuration

| Control | Where to change it | Effect |
| :--- | :--- | :--- |
| Microphone permission | Watch Settings → Apps → `ClawHark` | Required for recording. Restore it here if permission is denied permanently. |
| Notifications | Watch Settings → Apps → `ClawHark` | Enables recording notifications and resume reminders. Denying it does not prevent recording. |
| Battery optimization | Android battery optimization settings | Allow `ClawHark` to reduce interruptions while the watch is stationary with its screen off. The app offers these settings on first launch if it is not already exempt. |
| Google account | Long-press to sign out, then tap **LINK** | Changes where pending and future recordings are uploaded. |

# Reliability and Privacy

After reboot, the app attempts to resume recording if you left it enabled. If Android rejects the background start or microphone access, it shows a resume notification when notification permission is available. Otherwise, open the app manually. Automatic capture after reboot still needs testing on the Pixel Watch 3 running Wear OS 7; Android documents [restrictions and exceptions for microphone foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone).

If Google authorization is revoked during recording, audio stays local and the stop control remains available. Stop recording and link Drive again to resume uploads.

An abrupt process termination can leave the current MP4 chunk unplayable. Incomplete files are preserved locally; only temporary files with a readable audio track are published for upload. Continuous microphone capture consumes battery even during silence. Battery life, codec behavior, and reboot capture need verification on the intended watch.

The app requests the limited `drive.file` scope and uploads audio directly to Google. Access and refresh tokens are kept in encrypted on-device preferences. It has no developer-operated server or telemetry collection. See [PRIVACY.md](PRIVACY.md) for storage, transfer, and account controls.

# License

[MIT](LICENSE), with the original copyright notice preserved.
