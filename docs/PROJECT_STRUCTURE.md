# Project structure and extension guide

## Current layout

```text
.
├── .github/workflows/                 # Android build, emulator tests, release workflows
├── app/
│   ├── build.gradle.kts               # Android plugin, ABI flavors, SDK and dependencies
│   ├── proguard-rules.pro             # Release shrinker rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml    # Android entry points, permissions and intent filters
│       │   ├── java/com/search/browser/
│       │   │   ├── BrowserApp.kt      # Process-wide application initialization
│       │   │   ├── browser/           # Address resolution, tab labels and HTML documents
│       │   │   ├── downloads/         # Download models, file helpers, APKs and foreground service
│       │   │   └── ui/MainActivity.kt # Main screen and Android/Cefrium event orchestration
│       │   └── res/                   # Android drawables, values and XML resources
│       └── test/java/com/search/browser/
│           ├── BrowserAppProcessTest.kt
│           ├── browser/               # Tests for browser helpers
│           └── downloads/             # Tests for download helpers and models
├── docs/                              # Maintainer and architecture notes
├── scripts/android/                   # Device/emulator runtime verification
├── build.gradle.kts                   # Root Gradle plugin declarations
├── gradle.properties                  # Gradle runtime settings
└── settings.gradle.kts                # Included Gradle projects
```

## Package responsibilities

- `com.search.browser`: Android application bootstrap. Keep process-wide startup here; do not place feature logic in `BrowserApp`.
- `com.search.browser.browser`: small, reusable browser-domain helpers such as address normalization, tab labels, and local HTML document checks.
- `com.search.browser.downloads`: download data and pure helpers, archive handling, speed calculation, and the Android foreground service that retains active download work.
- `com.search.browser.ui`: the Activity entry point and current screen/event coordinator. Keep new reusable domain logic out of this file; add it to the owning feature package and call it from the UI layer.

The project intentionally remains a single Android application module. ABI flavors, generated `R`/`BuildConfig`, Android resources, and release workflows keep their standard Gradle/Android locations; a multi-module Gradle split is not required for this file-level organization.

## Adding or upgrading a feature

1. Put reusable logic in the package that owns the behavior (`browser` or `downloads`). Give new feature areas their own package rather than adding another unrelated helper to `MainActivity`.
2. Add a focused unit test in the matching `app/src/test/java/com/search/browser/<feature>/` directory. Keep the test package aligned with the production package so Kotlin `internal` helpers remain testable.
3. If an Android component is moved, update its fully qualified name in `app/src/main/AndroidManifest.xml` and update any explicit component references in the emulator script.
4. If an automation script moves, update every `.github/workflows/*.yml` call site in the same commit.
5. Keep release-signing material out of source control. CI creates `app/release-key.jks` from repository secrets and removes it after the release build; the local path is ignored by Git.

## Verification

Run unit tests and build each installable debug flavor:

```bash
gradle --no-daemon testEmulatorDebugUnitTest assembleEmulatorDebug assemblePhoneDebug assembleArm64Debug
```

Run the device/emulator verification script with an x86_64 emulator APK and its API level:

```bash
bash scripts/android/run-browser-emulator-test.sh <path-to-emulator-debug-apk> <api-level>
```

The GitHub Actions workflow `Browser Android build and emulator tests` runs the same unit-test/build matrix and API 30/35/36 emulator checks on pushes and pull requests targeting `main`.
