# Pandemonium Android Client

A small Android WebView client for `https://claras.page`, maintained separately from the Pandemonium server. Android 8.0 or later is supported. There are no production library dependencies and no JavaScript/native bridge.

The first launch opens Pandemonium's shared sign-in at `/ui`. Sign in there with the normal password and optional TOTP, then use the site's Daemon, Work, and Finance navigation. Subsequent launches restore the last page when a shared session is available. Work's separate legacy password form does not create a shared Android grant.

The shell handles Android back navigation, keyboard/system-bar insets, file picking, page-load progress, and connection retry. Links outside the exact HTTPS origin open in the appropriate external app. TLS errors and cleartext requests are not bypassed.

Camera access is requested on demand for live scanning on the exact `https://claras.page` origin. Android asks for camera permission the first time; microphone and unrelated WebView permissions are never granted. Navigating away cancels pending web camera requests. Image uploads offer **Take photo** or **Choose file**; capture-enabled fields launch the camera directly. Photos use temporary, individually granted content URIs in the app cache. Cancelled captures are deleted and older captures are cleaned up when starting a new photo.

## Sessions

- Web access cookies last seven days. They remain HttpOnly and are flushed to WebView's private persistent cookie store on navigation and backgrounding.
- After shared sign-in, native code enrolls a refresh grant. Its token and binding client id are AES-GCM encrypted with an Android Keystore key; backup and device transfer are excluded.
- The foreground app checks session state on navigation, resume, and every 30 seconds. It renews access during its final day, or before loading a page after expiry. Fresh sessions do not cause network refreshes.
- A grant expires after 90 days without a successful renewal. Password/TOTP changes, server-side revocation, or logout require sign-in again.
- Each refresh uses a fresh random successor. The app saves it before sending the request so a lost response can retry the same pair without losing access. Network failures retain the pending state. The server rejects other reuse and revokes the grant.
- Refresh credentials are never sent to page JavaScript, browser storage, URLs, external apps, or logs. Clearing Android app data removes this device's stored credentials.

The server must provide `POST /api/android/enroll` and `POST /api/android/refresh`, documented in Pandemonium's `docs/operations/control.md`. The client sends `X-Pandemonium-Client: android`, the WebView user agent, and the binding cookie. Enrollment additionally sends the valid access cookie. Success returns expiry metadata; refresh installs the returned HttpOnly cookies.

## Build and validate

Install JDK 17 or 21 and Android SDK platform 35. Set `ANDROID_HOME` to the SDK directory (or use an untracked `local.properties`). The checked-in Gradle wrapper pins the build toolchain.

```powershell
.\gradlew.bat --no-daemon assembleDebug assembleDebugAndroidTest lintDebug
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <device> shell am instrument -w page.claras.pandemonium.test/androidx.test.runner.AndroidJUnitRunner
```

Build the application and instrumentation APK together after source changes. The instrumentation suite uses synthetic credentials and a fake transport to check real Keystore encryption, cookie persistence, enrollment, lost-response recovery, revoked grants, logout, and origin restrictions. Backend tests separately exercise the actual HTTP protocol and durable server store.

To verify that the encrypted grant and HttpOnly cookie survive actual process death on a disposable test device, run the two-phase fixture (it clears test app session state afterward):

```powershell
adb -s <device> shell am instrument -w -e class page.claras.pandemonium.ProcessPersistenceTest -e persistencePhase seed page.claras.pandemonium.test/androidx.test.runner.AndroidJUnitRunner
adb -s <device> shell am force-stop page.claras.pandemonium
adb -s <device> shell am instrument -w -e class page.claras.pandemonium.ProcessPersistenceTest -e persistencePhase verify page.claras.pandemonium.test/androidx.test.runner.AndroidJUnitRunner
```

A real-device acceptance pass should cover sign-in, app force-stop/relaunch, keyboard and file picker, navigation among sites, airplane-mode recovery, and logout. Emulator tests and successful packaging do not establish real-device smoothness.

Debug APKs use Android's development signing key. Release signing requires a separately retained private key; do not commit signing material, passwords, captures, or local SDK paths.

For a signed release, provide `PANDEMONIUM_SIGNING_STORE` and `PANDEMONIUM_SIGNING_PASSWORD` through the local process environment, using key alias `pandemonium`, then run `gradlew.bat assembleRelease`. Without those values the release build is unsigned. Retain the same signing key for future updates; changing it prevents an in-place upgrade.

## Ownership

This repository owns only the Android shell and native credential lifecycle. Pandemonium owns authentication policy, refresh rotation/revocation, module pages, and responsive web styling. Website changes deploy from the server repository and are picked up without rebuilding this client.
