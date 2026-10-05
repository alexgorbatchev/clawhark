---
name: upload-watch-apk
description: Use when uploading, installing, or updating a ClawHark APK on alexgorbatchev's Pixel Watch 3 using wireless ADB.
author: alexgorbatchev
metadata:
  created_on: 2026-10-05 06:49
  last_modified: 2026-10-05 06:53
  status: current
---

## Select the APK and watch

Treat “upload the APK to my watch” as installing the app through ADB. Create or update this skill without installing anything when the request is only about skill authoring.

Use the user-selected APK. If building is requested or no suitable artifact exists, follow the repository's `AGENTS.md` build instructions. Prefer the signed debug artifact `app/build/outputs/apk/debug/app-debug.apk` for this development watch. The package is `ai.etti.clawhark`; the activity is `com.ettlinger.wearrecorder.MainActivity`. Verify these against the current manifest and Gradle configuration and inspect the selected APK's package and launchable activity with SDK `aapt dump badging` before deployment. Stop if that APK belongs to another package.

Read [references/setup.md](references/setup.md) when ADB, SDK paths, signing tools, or the watch connection need setup. Set `CLAW_ADB` to the verified ADB executable, `CLAW_APK` to the selected existing APK, and `CLAW_APKSIGNER` to the verified signing-tool executable. These variables are executable/file paths, not commands with embedded arguments.

```bash
"$CLAW_ADB" devices -l
"$CLAW_ADB" mdns services
```

Select a row whose state is `device`, and set `CLAW_WATCH_SERIAL` to its exact serial. This Pixel Watch 3 can appear both as an IP:port and an `adb-…._adb-tls-connect._tcp` discovery name. Use one transport; never install twice just because both appear. If the target is ambiguous between distinct watches, ask which one to use. Verify its identity:

```bash
"$CLAW_ADB" -s "$CLAW_WATCH_SERIAL" get-state
"$CLAW_ADB" -s "$CLAW_WATCH_SERIAL" shell getprop ro.product.model
"$CLAW_ADB" -s "$CLAW_WATCH_SERIAL" shell getprop ro.product.device
```

Expect `device`, `Pixel Watch 3`, and `luna` for this watch. Use `-s "$CLAW_WATCH_SERIAL"` for every subsequent device command, even when only one row exists. Do not use a remembered IP, port, or discovery serial without checking it in this session.

## Install and launch

Verify the APK's signature before installing:

```bash
"$CLAW_APKSIGNER" verify --verbose "$CLAW_APK"
"$CLAW_ADB" -s "$CLAW_WATCH_SERIAL" install -r "$CLAW_APK"
```

Require signature verification and installation to exit successfully, with `Success` from ADB, before proceeding. Use `install -r` to retain app data, including the linked account and queued recordings. Tell the user that updating can interrupt active capture. Keep the existing signing key; a different debug/release certificate can prevent replacing the installed app. On signature mismatch, downgrade rejection, or unsigned APK, report the exact error and stop. Do not uninstall, clear data, delete recordings, or add downgrade flags to force installation.

Open the app after successful installation:

```bash
"$CLAW_ADB" -s "$CLAW_WATCH_SERIAL" shell am start -W -n ai.etti.clawhark/com.ettlinger.wearrecorder.MainActivity
"$CLAW_ADB" -s "$CLAW_WATCH_SERIAL" shell pm path ai.etti.clawhark
"$CLAW_ADB" -s "$CLAW_WATCH_SERIAL" shell dumpsys activity services ai.etti.clawhark
```

Require a successful activity launch and an installed package path. Preserve explicit STOP and sign-out choices; opening the app is not authorization to toggle recording back on. Let the user grant microphone/notification permissions or link Drive on the watch if needed. Do not print credentials or overwrite `oauth_config.json`.

## Verify and report

Separate “installed,” “launched,” and “recording verified.” A foreground service or UI label alone does not prove microphone capture. For a debug build, inspect recording metadata and service logs:

```bash
"$CLAW_ADB" -s "$CLAW_WATCH_SERIAL" shell run-as ai.etti.clawhark ls -l files/recordings
"$CLAW_ADB" -s "$CLAW_WATCH_SERIAL" logcat -d -s WR.Service
```

Check fresh microphone-read logs and compare active chunk size across observations. Voice detection can leave file size unchanged during silence; do not declare failure from that alone. To test background capture, move to the watch home screen, observe for 30 seconds, and check again. Confirm the app is off-screen rather than inferring it. Debug `run-as` is unavailable for ordinary release builds; report that limitation and use device-visible status or available service logs instead. Do not download or play private audio merely to validate installation.

If ADB disconnects, run discovery again and retry once with a freshly verified connection. If still unavailable, report the last completed step and request the connection details from [setup](references/setup.md). Do not restart the shared ADB server, reboot the watch, or repeatedly reinstall as a connection workaround.

Save command results under the project's ignored `.tmp/`, excluding credentials and pairing codes. Report the APK path, selected watch, install/launch outcomes, capture evidence, and remaining checks. Leave automatic recording after physical reboot marked untested until an authorized reboot test actually confirms it.
