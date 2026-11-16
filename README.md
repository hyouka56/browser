# Browser — Chromium Android browser

Browser embeds Chromium through Cefrium 0.20.0. The project uses a native Chromium surface, a dark address bar, Chromium ad blocking, and Cefrium download management with a foreground service that keeps active transfers alive when the Browser task is dismissed.

## Rendering engine

- Embedded engine: Cefrium 0.20.0
- Chromium version: 156.0.8078.88
- Minimum Android API: 29
- Emulator Debug ABI: x86_64
- Phone Release ABI: arm64-v8a

The app creates its browser with the Cefrium surface API. If Cefrium reports that a renderer terminated, the app closes the failed browser instance and creates a fresh Chromium browser. It reloads the last URL whose surface bitmap passed a visible-pixel check.

The black browser UI, address bar, Chromium ad blocking, in-app download list, download pause/resume/cancel actions, and saved download history remain in the app.

Active downloads keep the Cefrium browser process alive through an Android `dataSync` foreground service. Removing Browser from the Recents screen does not close the active download; the service stops after the transfer and its copy into the public Downloads folder finish. Android force-stop, device shutdown, or the operating system terminating the process can still interrupt an in-progress transfer.

## Build variants

Every GitHub Actions run builds:
- `emulatorDebug`: x86_64 Debug APK for the API 30 and API 35 emulator jobs.
- `phoneRelease`: arm64-v8a unsigned Release APK for 64-bit ARM Android phones.

The build runs unit tests before creating both APK variants. Release minification remains enabled; Cefrium 0.20.0 supplies consumer rules for the native engine.

## Emulator verification

The API 30 and API 35 emulator jobs each install the x86_64 Debug APK, load `https://example.com/`, and verify the page title and loading-state callback. They then call Cefrium's surface-bitmap capture API and record the visible-pixel ratio, before taking a device screenshot.

A debug-only test navigates to Chromium's renderer-crash test URL. The app must receive Cefrium's renderer-termination callback, close the failed browser, create a replacement, reload Example Domain, and produce another visible surface bitmap. A second device screenshot is checked for black/near-black output. The run fails if recovery does not complete or the app process exits.

The forced renderer termination is only enabled in Debug builds. It exercises the same callback and browser-recreation path used for unexpected renderer termination; it does not claim to reproduce every device-specific GPU or driver fault.

Each emulator job uploads screenshots, Logcat, package/activity information, and a JSON report.

## Build artifacts

- `Browser-debug-APK`: x86_64 emulator Debug APK.
- `Browser-release-APK-unsigned`: arm64-v8a phone Release APK (unsigned).
- `Browser-unit-test-report`: Gradle unit-test results.
- `Browser-emulator-API-30-*` and `Browser-emulator-API-35-*`: runtime logs, screenshots, device details, and pixel-verification reports.

The workflow does not publish a GitHub Release or sign the APK.
