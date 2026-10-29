# Locky

A light, fast Android browser (Kotlin + Jetpack Compose) with built-in ad, popup and redirect blocking,
a data saver mode, a multi-connection downloader and an encrypted password vault.

## Features
- **Ad & tracker blocking** - domain blocklists (`app/src/main/assets/ads.txt`, `trackers.txt`) plus cosmetic hiding of empty ad slots.
- **Popup blocker** - windows opened without a user tap are blocked.
- **Redirect guard** - automatic cross-site redirects (no tap) are blocked; you can allow one from the banner.
- **Data saver** - no images, no video/fonts, trackers blocked.
- **Fast downloads** - up to 8 parallel connections with pause/resume (when the server supports range requests).
- **Passwords** - stored as AES-256-GCM ciphertext with a key held in the Android Keystore; never plain text.
- **Security** - HTTPS-only upgrade, third-party cookie blocking, no certificate-error bypass, lookalike-domain warnings,
  no file/content access from pages, no JS bridges, screenshots blocked in private tabs / password screen.
- **App lock** (optional) - fingerprint / face / screen lock when Locky opens.
- **Private tabs** - no cache or history kept.

## Build
Open in Android Studio (Koala or newer) and run, or:

    ./gradlew assembleDebug

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Pushing to GitHub also builds it via Actions (see the *Build APK* workflow artifacts).

## Known limits
- No server-side compression like Opera Mini; Data saver works inside the browser only.
- Private tabs share WebView's cookie jar with normal tabs (cookies are not isolated).
- Phishing warnings are offline heuristics, not a full blocklist. Safe Browsing is also enabled in WebView.
