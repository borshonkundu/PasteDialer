# Paste Dialer (Android 5.0+ / API 21+, pure Java, zero dependencies)

## Build
**Android Studio (easiest):**
1. File > Open > select the `PasteDialer` folder (JDK 17 is bundled; accept the Gradle sync).
2. Build > Build APK(s)  ->  `app/build/outputs/apk/debug/app-debug.apk`
3. Copy the APK to the phone and install (allow "Install unknown apps").

**Command line** (JDK 17 + Android SDK, with a Gradle 8.0+ install):
    gradle wrapper --gradle-version 8.0
    ./gradlew assembleDebug
For a smaller, shrunk release APK: `./gradlew assembleRelease` (then sign it with apksigner / Studio's Generate Signed APK).

## Use
1. Open the app, tap **Start bubble**, grant "Display over other apps" (and phone state).
2. Copy a number or PIN anywhere. Make or take a call. The **123** bubble appears (drag it anywhere).
3. Tap it, tap **Paste**, then press the highlighted digit on the phone's keypad and tap **Next**.
4. In the main app: **Paste**, insert `,` (2 s pause) / `;` (wait), then **Call now** to dial a number
   and have Android send the digits as DTMF automatically after the call connects, e.g. `0123456789,,1234#`.

## Limits (Android security, not bugs)
- Third-party apps cannot type into or inject DTMF into a live call. That requires being the default dialer (InCallService, API 23+).
- "Tone" plays locally only; it is not sent into the call.
- On Android 10+ background apps cannot read the clipboard, hence the invisible PasteActivity.
- READ_CLIPBOARD does not exist for normal apps; PROCESS_OUTGOING_CALLS is deprecated and not needed.
- If you raise targetSdk to 34+, add FOREGROUND_SERVICE_SPECIAL_USE and a foregroundServiceType to the service.
