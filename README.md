# Browser — Android WebView build and emulator tests

This repository builds the Android browser app and runs it in GitHub Actions emulators. The emulator workflow and scripts are maintained here; the source emulator template repository is not modified.

## Rendering engine

The app uses the Android System WebView provider through the stable Android WebView APIs. It no longer embeds the Cefrium/CEF browser engine. The AndroidX WebKit integration is pinned to stable 1.18.0; the Chromium runtime itself is supplied and updated by the Android WebView provider on each device.

The app handles WebView renderer termination by removing and destroying the affected WebView instance, creating a fresh instance, and reloading the last successfully rendered URL. This follows Android's documented onRenderProcessGone recovery requirements.

## GitHub Actions emulator matrix

Every workflow run builds the Debug and unsigned Release APKs, runs unit tests, then downloads the Debug APK into two separate temporary emulators:

| Emulator | Image | Status |
|---|---|---|
| Android API 30 | Google APIs, x86_64, Pixel 5 profile | Required |
| Android API 35 | Google APIs, x86_64, Pixel 5 profile | Required |

KVM is enabled before emulator startup. Both jobs run in GitHub Actions in this repository. Each emulator is discarded after its job.

## Runtime test coverage

The emulator test script:

1. Verifies that the running emulator reports the requested API level.
2. Installs the Debug APK and records device ABI information and the active WebView provider.
3. Opens https://example.com/ through a debug-only test launch argument.
4. Checks WebView DOM readiness and waits for postVisualStateCallback, which confirms that a page frame was drawn.
5. Triggers the WebView test crash URL, verifies onRenderProcessGone, and checks that the app recreated the WebView and rendered the last successful page again.
6. Checks that the app process survives and remains the resumed Activity.
7. Saves Logcat, screenshots, package/activity dumps, WebView-provider information, and report.json as a workflow artifact.

The deliberate renderer restart is a test-only path available in Debug builds. It is not enabled in Release builds.

## Build artifacts

- Browser-debug-APK: installable Debug APK used by emulator tests.
- Browser-release-APK-unsigned: unsigned Release APK.
- Browser-unit-test-report: Gradle unit-test report.
- Browser-emulator-API-30 and Browser-emulator-API-35: emulator screenshots, Logcat, runtime details, and JSON reports.

## Limitations

The Android System WebView runtime is supplied by the device's installed provider, so its Chromium build can vary by Android device and provider update. The stable AndroidX WebKit library version is pinned; the underlying system WebView binary is not bundled inside this APK.

The ad-block toggle currently uses a compact built-in host list rather than downloading the full EasyList subscription used by the removed Cefrium integration. Download handling is provided by Android DownloadManager.
