# Pandemonium Android Client

A small Android WebView client for `https://claras.page`, maintained separately from the Pandemonium server. Android 8.0 or later is supported. ZXing Core renders the native loyalty QR widget; there is no JavaScript/native bridge.

The first launch opens Pandemonium's shared sign-in at `/ui`. Sign in there with the normal password and optional TOTP, then use Work and Finance navigation. The Android shell hides the Daemon tab; desktop navigation and direct Daemon routes remain available. Subsequent launches restore the last page when a shared session is available. Work's separate legacy password form does not create a shared Android grant.

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

## Mail and calendar widgets

Mail and Calendar are native, resizable, scrolling home-screen widgets with a
translucent background shared with the loyalty widget. Mail shows Aqua Mail's
local unread messages and opens the selected email in Aqua Mail using the exact
`view_uri` supplied by its read-only integration. Pandemonium does not render a
mail inbox or message reader. The header opens Aqua Mail normally; a missing
message never falls back to a different email or the inbox. Calendar shows every
planned Work shift in the current Monday–Sunday week in Europe/Berlin, including
completed shifts earlier in the week. The header labels the cached week, and taps
open native shift details. Approved shift plans and confirmed nonworking days
follow Work's existing schedule rules.

Calendar refresh uses the existing native Android session and the read-only
`work.schedule.currentWeek` API. The source timestamp reflects Work's projection
cache; refreshing a widget does not force a new Calendar-provider read within
that cache's local day. A missing or failed Work API is shown as unavailable,
with the last successful current-week snapshot retained and dated. Older personal
agenda caches are not displayed as Work shifts. There is no shift-count cap;
responses over 1 MiB or a combined encrypted cache over 512,000 characters fail
explicitly rather than silently truncating.

Mail requires Aqua Mail
(`org.kman.AquaMail`) and its `org.kman.AquaMail.datax.READ_MESSAGES` permission;
tap the widget header to grant access if the installed version requests it at runtime.
Only message summaries and viewing links are read; no bodies, account credentials,
message actions or notification access are requested. This uses Aqua Mail's
[published integration API](https://forum.aqua-mail.com/index.php?topic=4476.0),
which exposes unread messages. No provider passwords or tokens are configured in
the widgets. Android schedules
background refresh around every 30 minutes and may defer it while offline or in
battery-saving modes. Opening the app also refreshes installed widgets. Last good
snapshots are encrypted with Android Keystore, remain readable offline, show their
age when stale, and are cleared when the session is revoked or signed out.

The server must provide the owner-only `work.schedule.currentWeek` RPC. Mail and calendar are
read-only; the widget detail views do not send mail or edit events.

## Loyalty QR widget

The home-screen widget shows static Action, REWE, and Lidl Plus passes. It reads the existing authenticated Finance wallet endpoint, stores only non-expiring QR payloads encrypted with Android Keystore, and keeps them available offline. Tap a tile to open its QR code full-screen. Opening Pandemonium refreshes the widget from the current Finance wallet.

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
