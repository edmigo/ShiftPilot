# ShiftPilot V4.3 — Share Wolt Screenshot

## New workflow

1. A courier receives an order in Wolt.
2. Take a normal Android screenshot.
3. Tap **Share** on the screenshot.
4. Choose **ShiftPilot**.
5. ShiftPilot opens directly on the Order screen.
6. The screenshot is OCR'd locally using the existing ShiftPilot OCR flow.
7. Restaurant / address / payment / distances are pre-filled when recognized.
8. The courier verifies the fields and decides whether to accept the order.

There is no background scanning of Wolt.

## Privacy

The shared screenshot is intercepted by the installed PWA service worker and temporarily stored in local browser Cache Storage.
It is deleted before OCR begins.
The image is not uploaded to the ShiftPilot FastAPI backend by this feature.

## Files changed

- `index.html`
- `manifest.json`
- `sw.js`

No database migration and no backend API change are required.

## Install

Copy these three files to the ShiftPilot project root:

- `index.html`
- `manifest.json`
- `sw.js`

Then refresh/restart the site.

Because Android registers Web Share Targets from the installed PWA manifest, if **ShiftPilot does not appear in the Android Share menu**, do this once:

1. Remove the currently installed ShiftPilot PWA from the phone.
2. Open `https://couriers.duckdns.org` in Chrome.
3. Install ShiftPilot again as an app.
4. Take a screenshot.
5. Tap Share.
6. ShiftPilot should now appear as a share destination.

## Service worker cache

Asset cache version is now:

`shiftpilot-v4-3`

The shared screenshot cache is:

`shiftpilot-share-v1`
