# ClawHark Privacy Policy

ClawHark captures microphone audio on your watch and uploads completed recordings to the Google Drive account you authorize. This repository provides the watch app and Drive uploads.

## Audio and storage

Audio is stored in the app's private internal storage as AAC/M4A recordings. Completed files are uploaded to a `ClawHark` folder in your Google Drive. Local files are deleted only after Drive acknowledges the matching file ID, name, size, and checksum.

Upload failures preserve recordings for retry. When storage fills, recording pauses rather than deleting unuploaded audio. Incomplete recordings from a crash may remain on the watch for recovery or diagnosis.

Stopping recording and signing out preserve pending recordings. Linking another account sends that pending queue to the newly authorized account. Uninstalling the app removes local audio, logs, and credentials. Recordings already in Drive must be deleted separately.

Transfers to Google use HTTPS. ClawHark does not add its own encryption to audio files. On-device protection and Google Drive storage protection are provided by their respective platforms.

## Google authorization

The app uses Google's device authorization flow with the `drive.file` scope. This permits access to files created by the app or explicitly shared with it, rather than general access to your Drive.

Account access and refresh tokens are kept in encrypted preferences backed by the Android Keystore. OAuth client credentials are bundled with the app; they are not account access tokens.

Signing out clears local account credentials, stops recording, cancels uploads, and attempts to revoke Google's authorization. If offline or interrupted, server-side revocation may not complete. You can revoke access at [Google Account permissions](https://myaccount.google.com/permissions).

## Services and logs

Google handles authorization and stores uploaded audio under its [Privacy Policy](https://policies.google.com/privacy). ClawHark has no developer-operated server, analytics, advertising, transcription service, or telemetry collection.

Local diagnostic logs record service state, file-transfer status, and errors. They rotate at approximately 2 MB. Authentication tokens and authorization codes are not intentionally logged.

## Controls

You can stop recording, sign out, revoke Google's authorization, delete uploaded recordings in Drive, or uninstall the app to remove its local data.
