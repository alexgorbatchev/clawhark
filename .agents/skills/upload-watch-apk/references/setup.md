---
created_on: 2026-10-05 06:49
last_modified: 2026-10-05 06:49
status: current
---

## Host tools and APK

Locate ADB through `command -v adb`, or the verified SDK's `platform-tools/adb`. Resolve the SDK from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or the repository's ignored `local.properties`; if none is configured, inspect the host's SDK installation. Set `CLAW_ADB` to the resulting executable path and run `"$CLAW_ADB" version`. Avoid assuming ADB is on PATH or hardcoding another machine's filesystem paths.

Use SDK Platform Tools 30.0.0 or later for Wear OS wireless debugging. Installing an existing signed APK needs Platform Tools, not Android Studio. Building this repository additionally needs JDK 17 and SDK platform/build tools matching its Gradle configuration; follow `AGENTS.md` rather than installing or changing tools without a deployment need.

Locate `apksigner` on PATH or in a verified installed SDK `build-tools` version directory and set `CLAW_APKSIGNER` to its executable path. Set `JAVA_HOME` to a verified JDK if that tool cannot find Java. The debug APK is signed during `assembleDebug`; `app/build/outputs/apk/release/app-release-unsigned.apk` is not installable until signed. Do not generate a replacement key to bypass a certificate mismatch.

Set `CLAW_APK` to an existing artifact from the intended checkout. If a fresh build is needed, retain the user's OAuth asset and signing configuration, run the relevant repository checks, and verify the resulting artifact. An APK can contain extractable OAuth configuration; keep it out of Git and public uploads.

## Wireless debugging

Keep the Mac and watch on the same Wi-Fi network. On the Pixel Watch 3, open **Settings → Developer options**, enable **ADB debugging**, then **Wireless debugging**, and accept the network prompt if shown. Follow [Google's developer-options instructions](https://developer.android.com/studio/debug/dev-options) if Developer options is missing.

First check `devices -l` and `mdns services`; an already paired watch can reconnect automatically. Do not ask for another pairing code while a verified connection already works.

If paired but disconnected, use the current IP:port under the **main Wireless debugging screen**, or the discovered `_adb-tls-connect._tcp` service. Set `CLAW_WATCH_ENDPOINT` to that verified connection endpoint:

```bash
"$CLAW_ADB" connect "$CLAW_WATCH_ENDPOINT"
"$CLAW_ADB" devices -l
```

If pairing is actually needed, open **Wireless debugging → Pair new device** on the watch. Set `CLAW_PAIR_ENDPOINT` to the IP:port shown there, then enter its fresh code at ADB's prompt:

```bash
"$CLAW_ADB" pair "$CLAW_PAIR_ENDPOINT"
```

Require pairing success, then return to the main screen for the connection endpoint. The pairing and connection ports differ and can change when debugging restarts. Pairing success alone is not a connected device. Never store the code in the skill, a script, or a validation log.

For `offline`, connection refusal, or a missing watch, ask the user to reopen Wireless debugging and confirm Wi-Fi, then rediscover the endpoint. A watch showing the Mac as paired does not establish a live ADB transport.

## Official references

- [Wear OS wireless debugging and pairing](https://developer.android.com/training/wearables/get-started/debug-wifi)
- [ADB targeting, installation, and activity launch](https://developer.android.com/tools/adb)
- [APK signature verification](https://developer.android.com/tools/apksigner)
