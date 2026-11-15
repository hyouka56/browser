# Browser

Native Android browser built with Kotlin and a bundled Chromium engine.

- App name: Browser
- Application ID: com.search.browser
- UI: minimal black native Android UI, white text/icons, bottom search field
- Engine: Cefrium 0.20.0, embedding Chromium 156.0.8078.88
- Ad blocker: EasyList network filtering, controlled by the shield button
- Downloads: in-app list with filename, live speed, percentage, progress, and pause/resume/cancel controls while active

## Build requirements

- JDK 25
- Gradle 9.7.1
- Android Gradle Plugin 9.4
- Android SDK platform 37
- Android minSdk 29

Run: gradle clean testDebugUnitTest assembleDebug

Installable debug APK: app/build/outputs/apk/debug/app-debug.apk

GitHub Actions builds this APK after pushes to main and on manual workflow dispatch. The workflow uploads an artifact named Browser-debug-APK.

## Engine and limitations

Cefrium embeds Chromium instead of using Android System WebView. It carries a large native engine; the upstream project reports approximately 89 MB compressed for ARM64 before the rest of the APK is counted. The app targets Cefrium-supported arm64-v8a, armeabi-v7a, and x86_64 ABIs.

Cefrium describes its SDK as pre-1.0/public beta. Its API may change before 1.0, so this app pins version 0.20.0. EasyList filtering is network-level; cosmetic element hiding is not included. The browser does not bypass CAPTCHAs or website bot checks.

Upstream references:
- https://cefrium.com/quickstart/
- https://cefrium.com/releases/
- https://cefrium.com/guides/ad-blocking/
- https://cefrium.com/docs/com/cefrium/CefriumBrowser.DownloadItem.html
