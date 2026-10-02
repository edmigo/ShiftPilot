# ShiftPilot Wolt Diagnostic V1

This is the first diagnostic Android prototype for deciding how ShiftPilot can automate Wolt order capture **without bypassing platform security**.

It tests three independent sources:

1. **Notification Listener** — what Wolt exposes in Android notifications.
2. **Accessibility tree** — what visible Wolt UI text/IDs Android exposes after explicit user permission.
3. **MediaProjection capture diagnostic** — whether the current Wolt screen can be captured normally. It analyzes a single frame and does **not save the screenshot**. A secure/blocked screen should appear nearly black/blank.

The app does not click Wolt buttons, accept/reject orders, inject input, disable security flags, or bypass secure windows.

## Build

Open this folder in Android Studio (JDK 17+). If Android Studio asks for a Gradle distribution, use Gradle 8.10.x or let Android Studio create/download a wrapper, then Sync Project.

Required SDK: Android SDK 35.
Minimum phone version: Android 8 (API 26).

## First test on the courier phone

1. Install the debug APK.
2. Open **ShiftPilot Wolt Diagnostic**.
3. Leave the target package empty initially. The diagnostic auto-detects packages/app labels containing `wolt`.
4. Tap **Enable notification access** and enable only ShiftPilot.
5. Tap **Enable Accessibility diagnostic** and enable `ShiftPilot Wolt UI Probe`.
6. Open Wolt and wait for / display one incoming order offer.
7. Return to ShiftPilot and inspect/share the report.
8. For screenshot protection, tap **Test screen capture in 8 seconds**, approve Android capture permission, and immediately open the Wolt offer screen. After ~8 seconds, return to ShiftPilot.

## What we want from one real order

Look for these fields in the `NOTIF` and `A11Y` sections:

- restaurant / merchant
- payout / amount
- pickup distance
- delivery distance
- delivery area/address (if legally/appropriately exposed)
- offer timer / state

The `CAPTURE` result will say either approximately:

- `VISIBLE FRAME RECEIVED` — ordinary capture seems technically available on that screen, or
- `LIKELY BLOCKED / BLACK` — secure-window behavior is possible.

## Privacy

- No INTERNET permission is requested.
- Logs stay in the app's private storage unless the user taps **Share report**.
- Obvious phone-number patterns are redacted in diagnostic logs.
- Screenshot pixels are never written to disk by this prototype.

## Important Android / Play note

Accessibility access is sensitive. This diagnostic is intended for a controlled prototype on the courier's own device with explicit permission. A commercial Google Play release needs to comply with Google Play Accessibility API disclosure/consent and permitted-use requirements.


## Build APK without Android Studio

This project includes `.github/workflows/build-apk.yml`.

1. Create an empty GitHub repository.
2. Upload the entire contents of this project to the repository.
3. Open **Actions** → **Build ShiftPilot Diagnostic APK**.
4. Press **Run workflow**.
5. When the workflow finishes, download the artifact named:
   `ShiftPilot-Wolt-Diagnostic-V1-APK`
6. Extract `ShiftPilot-Wolt-Diagnostic-V1.apk` on your phone.
7. Android may ask you to allow **Install unknown apps** for your browser/file manager.
8. Install the APK.

The APK produced by this workflow is a debug APK, automatically signed by the Android build system and suitable for direct testing on a phone.


## IMPORTANT: repository layout

Upload the CONTENTS of this folder to the root of your GitHub repository.
The repository root must contain:

- `.github/`
- `app/`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `README.md`

Do not upload the outer folder itself as a nested directory.
