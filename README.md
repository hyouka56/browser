# Browser — Chromium Android browser

Browser embeds Chromium through Cefrium 0.21.0 (Chromium 156.0.8078.96). The project uses a native Chromium surface, readable dark app chrome without recoloring websites, popup tabs, per-site permission prompts, fullscreen media, system media controls, and Cefrium download management with a foreground service that keeps active transfers alive when the Browser task is dismissed.

## Rendering engine

- Embedded engine: Cefrium 0.21.0
- Chromium version: 156.0.8078.96
- Minimum Android API: 29
- Target Android API: 36
- ARM32 TV Lite APK ABI: armeabi-v7a
- ARM64 APK ABI: arm64-v8a
- Emulator Debug APK ABI: x86_64

The app creates its browser with the Cefrium surface API. If Cefrium reports that a renderer terminated, the app closes the failed browser instance and creates a replacement. Cefrium 0.21.0 also maps Android low-memory renderer termination into its renderer callback, allowing the app's recovery path to handle that condition.

The dark browser UI, concise tab labels (up to eight live tabs), address bar, in-app downloads, download pause/resume/cancel actions, and saved download history remain in the app. Website colors are left to each page. Inactive tabs are blurred and paused; only the selected tab keeps active media controls.

Active downloads keep the Cefrium browser process alive through an Android `dataSync` foreground service. Removing Browser from the Recents screen does not close an active download; the service stops after the transfer and its copy into the public Downloads folder finish. Android force-stop, device shutdown, or the operating system terminating the process can still interrupt an in-progress transfer.

## Build variants

Every GitHub Actions run builds:
- `emulatorDebug`: x86_64 Debug APK for the API 30 and API 35 emulator jobs.
- `phoneDebug`: armeabi-v7a TV Lite Debug APK for 32-bit ARM devices.
- `arm64Debug`: arm64-v8a Debug APK for 64-bit ARM devices.

The ARM32 TV Lite flavor disables ad blocking and custom page recoloring to reduce extra startup work. The build verifies each APK's native ABI and Android package signature.

## Emulator verification

The API 30, API 35, and API 36 emulator jobs install the x86_64 Debug APK, load `https://example.com/`, verify the page title and loading callback, and capture a visible surface bitmap. They also exercise the Debug-only renderer-termination recovery path.

These emulator jobs do not claim to reproduce an ARM32 TV's memory, GPU driver, or OEM process-management behavior.

## Build artifacts

GitHub Actions uploads signed Debug APKs for ARM32 TV Lite, ARM64, and x86_64 emulator testing. The workflow does not publish a GitHub Release or sign a production Release APK.
