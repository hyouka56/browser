# Browser — GeckoView Android browser

The Browser app uses Mozilla GeckoView as its bundled web-rendering engine. It does not depend on the Android System WebView provider and no longer uses the previous Cefrium integration.

## Engine and packaged ABIs

- GeckoView stable artifact: 160.0.20261104181552
- Debug builds bundle the x86_64 GeckoView runtime for GitHub Actions emulators.
- Release builds bundle the arm64-v8a GeckoView runtime for 64-bit ARM Android phones.
- Minimum Android API remains 29.

The app creates one process-lifetime GeckoRuntime and attaches a GeckoSession to its GeckoView. If GeckoView reports a content-process crash or kill, the app detaches the failed session, creates a replacement, and reloads the last successfully painted URL.

The tracking-protection switch controls GeckoView anti-tracking categories and per-session tracking protection. This is browser tracking protection, not an EasyList subscription-based ad filter.

## GitHub Actions emulator matrix

Every run builds a Debug APK and an unsigned Release APK, runs unit tests, then installs the Debug APK into two separate GitHub Actions emulators:

| Emulator | Image | ABI |
|---|---|---|
| Android API 30 | Google APIs, Pixel 5 profile | x86_64 |
| Android API 35 | Google APIs, Pixel 5 profile | x86_64 |

KVM is enabled before emulator startup. The original emulator template repository is read-only; all added workflow and test files are maintained in this repository.

## Runtime tests and evidence

The runtime test opens https://example.com/, checks the GeckoView engine version, page title, successful page-load callback, and first-contentful-paint callback. It captures a screenshot and checks that the page is visibly rendered.

The test then exercises the app's GeckoSession replacement path—the same path called by GeckoView onCrash and onKill callbacks—reloads the last successful URL, waits for another first-contentful-paint callback, and checks a second screenshot. The test invokes the recovery handler directly; it does not claim to synthesize a native Gecko content-process crash.

Each emulator uploads before/after recovery screenshots, the final screenshot, Logcat, device properties, package/activity/memory dumps, and report.json with page-render and screenshot pixel checks. A black or near-black screenshot fails the test even if the page-load callback fired.

## Build artifacts

- Browser-debug-APK: x86_64 Debug APK used in emulator jobs.
- Browser-release-APK-unsigned: arm64-v8a unsigned Release APK.
- Browser-unit-test-report: Gradle unit-test report.
- Browser-emulator-API-30 and Browser-emulator-API-35: screenshots, Logcat, device details, and JSON test reports.

The Release APK is unsigned. This workflow does not create a GitHub Release or sign the APK.
