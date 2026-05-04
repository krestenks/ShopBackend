# Android release signing key setup (ShopManager)

This project uses **self-hosted APK updates** (no Play Store). Android will only allow updating an installed app if:

1. `applicationId` (package name) is the same
2. the **signing key is the same**
3. `versionCode` increases

If you lose the release signing key, users will **not be able to update** — they would have to uninstall and install fresh (losing local app data).

## 1) Create a release keystore (one-time)

Create a folder that is **NOT committed to git** (example: `keystore/`).

### Windows (PowerShell)

`keytool` ships with the JDK. If you have Android Studio installed, you usually have a JDK available.

```powershell
mkdir keystore

keytool -genkeypair `
  -alias shopmanager `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000 `
  -keystore keystore\shopmanager-release.jks
```

### macOS / Linux

```bash
mkdir -p keystore

keytool -genkeypair \
  -alias shopmanager \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -keystore keystore/shopmanager-release.jks
```

You will be prompted for:

- keystore password
- key password (you can choose to reuse keystore password)
- certificate info (name/org/country). This is not used for security in the way HTTPS certs are; it’s just metadata.

### Sanity check (optional)

```bash
keytool -list -v -keystore keystore/shopmanager-release.jks
```

## 2) Store the keystore safely

Minimum recommended:

- Keep `keystore/shopmanager-release.jks` **out of git**
- Back it up in **two separate places**
- Store passwords in a password manager

Recommended approach:

- Password manager entry containing:
  - path to the keystore file
  - keystore password
  - key alias (`shopmanager`)
  - key password
- Copy the `.jks` to:
  - an encrypted external drive, or
  - a secure cloud vault (encrypted)

## 3) Configure Gradle signing locally (do not commit secrets)

Create a file **next to** the Android project’s `settings.gradle.kts` (i.e. repository root of the Android project), named:

`keystore.properties`

Example:

```properties
# DO NOT COMMIT THIS FILE
storeFile=keystore/shopmanager-release.jks
storePassword=REPLACE_ME
keyAlias=shopmanager
keyPassword=REPLACE_ME
```

The Android `app/build.gradle.kts` will be configured to read this file (or environment variables) and apply it for the `release` build.

## 4) Build a signed release APK

From the Android project root:

```bash
./gradlew :app:assembleRelease
```

APK output location (typical):

```
app/build/outputs/apk/release/app-release.apk
```

## 5) Bump versions correctly

In `app/build.gradle.kts`:

- Increase `versionCode` **every release** (monotonic)
- Update `versionName` (human-readable)

Example:

```kotlin
defaultConfig {
    versionCode = 3
    versionName = "1.0.3"
}
```

## 6) Compute SHA-256 for the update manifest

Windows:

```bat
certutil -hashfile app-release.apk SHA256
```

macOS/Linux:

```bash
sha256sum app-release.apk
```

Copy only the hex hash into the backend `version.json`.

## 7) Important warning

Do **not** change:

- `applicationId`
- the release keystore
- the alias

…after you have distributed any release.

If you do, Android will treat the new APK as a different publisher and will block upgrades.
