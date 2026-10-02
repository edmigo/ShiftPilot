# ShiftPilot Wolt Collector V2

V2 moves from a capture diagnostic to an **automatic Wolt screen collector**.

## What it does

- Uses the existing package id `com.shiftpilot.woltdiagnostic`, so `adb install -r` upgrades V1 in place.
- Detects when `com.wolt.courierapp` is foreground via Accessibility events.
- Starts a normal Android MediaProjection session after explicit user approval.
- While Wolt is foreground, reads changed frames in memory roughly every 1.25 seconds.
- Runs on-device ML Kit OCR on changed frames only.
- Extracts a first-pass order candidate:
  - payment in ₪ / ILS / NIS
  - one or more distances in km
  - restaurant hint
  - address hint
  - confidence
- Stores **no screenshot files**.
- Does **not** click Accept/Reject and does **not** send orders to the backend yet.

## Important OCR limitation in V2

This build uses ML Kit's bundled Latin text recognizer. It is reliable for numbers, `km`, `₪`, English/Latin text, but Hebrew restaurant/address text may be incomplete. That is intentional for this validation step. If the Wolt offer screen is mainly Hebrew, the next build should add a Hebrew-capable OCR engine after we see the V2 report.

## Build without Android Studio

The repository includes `.github/workflows/build-apk.yml`.

1. Upload the project to GitHub.
2. Open **Actions** → **Build ShiftPilot Collector V2 APK**.
3. Press **Run workflow**.
4. Download artifact `ShiftPilot-Wolt-Collector-V2-APK`.
5. Extract `ShiftPilot-Wolt-Collector-V2.apk`.

## Install / upgrade with ADB

From your `platform-tools` folder:

```powershell
.\adb.exe devices
.\adb.exe install -r -t "C:\path\to\ShiftPilot-Wolt-Collector-V2.apk"
```

Because V2 keeps the same application id, this upgrades V1. Android normally preserves granted Notification/Accessibility settings, but verify them after the update.

## Test

1. Open ShiftPilot Wolt Collector V2.
2. Confirm target package is `com.wolt.courierapp`.
3. Confirm Notification listener is ON.
4. Confirm Accessibility probe is ON.
5. Tap **Start automatic Wolt collector** and approve Android screen capture.
6. Open Wolt.
7. When a real incoming order/offer is visible, leave it on screen for several seconds.
8. Return to ShiftPilot.
9. Check **Last automatic order candidate**.
10. Tap **Share report** and send the report for parser tuning.

## Expected log

Example only:

```text
[OCR] Recognized 9 lines
text=Pizza Romeo box=...
text=₪34.00 box=...
text=1.2 km box=...
text=3.8 km box=...

[ORDER] AUTO ORDER CANDIDATE
payment=₪34.00
distances_km=1.20, 3.80
restaurant_hint=Pizza Romeo
confidence=0.85
```

## Next step after one real Wolt offer

Once we know the exact OCR layout and labels from the real Wolt offer screen, V3 can:

- crop only the relevant Wolt offer regions for faster OCR;
- add stronger Hebrew OCR if needed;
- deduplicate order ids/offers;
- calculate expected ₪/hour automatically;
- POST the order directly into the existing ShiftPilot backend.
