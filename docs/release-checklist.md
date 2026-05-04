# Private APK release checklist (ShopManager)

This repo contains a self-hosted update mechanism:

- Backend serves:
  - `GET /api/app/version.json` (no-cache)
  - `GET /api/app/download/{filename}` (APK)
- Android app checks `version.json`, downloads the APK, verifies SHA-256, and opens the installer.

## One-time setup

1. Create and store the release signing key
   - See: `docs/android-signing-setup.md`
2. Ensure `data/apk/` exists on the backend host (or in the container volume)
3. Ensure HTTPS is enabled (required)

## Every release

### 1) Bump version in Android

Edit `ShopManager/app/build.gradle.kts`:

- increment `versionCode` (must be higher than any previous release)
- set `versionName` (e.g. `1.0.3`)

### 2) Build a signed release APK

From the Android project root:

```bash
./gradlew :app:assembleRelease
```

APK output (typical):

```
app/build/outputs/apk/release/app-release.apk
```

Rename it to a versioned name, e.g.:

```
shopmanager-1.0.3.apk
```

### 3) Compute SHA-256

Windows:

```bat
certutil -hashfile shopmanager-1.0.3.apk SHA256
```

macOS/Linux:

```bash
sha256sum shopmanager-1.0.3.apk
```

Copy only the hex hash.

### 4) Upload APK to backend

Place the APK in:

```
data/apk/shopmanager-1.0.3.apk
```

(If the backend is deployed elsewhere, upload to the same directory on the server/container volume.)

### 5) Update the manifest

Edit:

```
data/apk/version.json
```

Set:

- `versionCode`
- `versionName`
- `apkUrl` (must be HTTPS, and point at `/api/app/download/shopmanager-1.0.3.apk`)
- `sha256`
- `required` (optional forced update)
- `releaseNotes` (optional)
- `minSupportedVersionCode` (optional)

### 6) Smoke test

1. On a phone with the previous version installed:
   - open app → check update → download → installer appears → update succeeds
2. On a fresh phone:
   - install APK → open app

## Troubleshooting

### “App not installed” / “Package appears to be invalid”

Often caused by:

- APK signed with the wrong key
- `applicationId` changed
- trying to install a lower `versionCode` than currently installed

### Update check doesn’t find updates

- Confirm `version.json` contains a higher `versionCode`
- Confirm caching isn’t happening (`/api/app/version.json` uses `Cache-Control: no-cache`)
