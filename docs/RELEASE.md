# Releasing a new version

iptvmamba checks the latest GitHub release once a day and offers the update in the bell at the top right.
Android installs an update **over** the old app (and keeps playlists, favorites, history and settings) only if
both APKs are signed with **the same key**. Lose the key and every user has to uninstall and start from scratch.

## 1. One-time: the signing key

### If version 1.0 was built with "Build APK" in Android Studio (debug key)

That APK is signed with the debug key of your computer:
`C:\Users\<you>\.android\debug.keystore` (Windows) or `~/.android/debug.keystore` (macOS/Linux).
To let 1.0 users update without losing anything, keep signing with exactly this key:

1. Copy `debug.keystore` to a safe place **outside** the project (and make a backup, e.g. on a USB stick).
2. Create `keystore.properties` in the project root (it is ignored by Git):

```properties
storeFile=C:/keys/ipvio-debug.keystore
storePassword=android
keyAlias=androiddebugkey
keyPassword=android
```

### Starting fresh with your own key (recommended for the future)

Android Studio: **Build > Generate Signed App Bundle / APK > APK > Create new…**, or:

```
keytool -genkeypair -v -keystore ipvio-release.jks -alias ipvio -keyalg RSA -keysize 4096 -validity 36500
```

Then the same `keystore.properties` with your file, alias and passwords.
Users of an APK signed with a different key have to uninstall once (Settings > Storage > Create backup first, then restore).

**Never commit the key or `keystore.properties`.** Both are already in `.gitignore`.

## 2. Every release

1. In `app/build.gradle.kts` raise `versionCode` (by 1) and `versionName` (e.g. `"1.2"`).
2. Build the signed APK: `gradlew assembleRelease` (or Build > Generate Signed App Bundle / APK).
   The file is `app/build/outputs/apk/release/app-release.apk`.
3. Rename it to **`iptvmamba-Stream.apk`**.
4. On GitHub: **Releases > Draft a new release**
   - Tag: `v` + versionName, e.g. `v1.2` (the app compares this tag with its own version)
   - Title and notes: what is new (the first lines appear in the app)
   - Attach `iptvmamba-Stream.apk`
   - **Publish release** (not "pre-release", the app ignores those)

The download link in the README and the Downloader code always point to the newest release:
`https://github.com/Emrodev/iptvmamba-Stream/releases/latest/download/iptvmamba-Stream.apk`

## 3. What users see

- Within a day the bell shows a red dot and "Update available: version 1.2". One click downloads and opens the installer.
- Settings > About > App update: check now, install, or turn off the automatic check.
- The first time, Android asks to allow "Install unknown apps" for iptvmamba.

Version 1.0 has no update check yet, so 1.0 users install 1.1 once by hand (Downloader code or link). From 1.1 on it works from inside the app.
