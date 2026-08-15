# Photos Screensaver — Maintenance Guide

## Tools & paths (one-time setup, already done)

| Tool | Path |
|------|------|
| ADB | `C:\android-sdk\platform-tools\adb.exe` |
| Gradle 8.5 | `C:\Users\jphel\tools\gradle-8.5\bin\gradle.bat` |
| Android SDK | `C:\android-sdk` |
| JDK 21 | `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` |

> Both tools used to live under `%TEMP%` and Windows' temp cleanup **gutted them**
> (2026-08-14): gradle's `bin/` and `lib/` were emptied, and platform-tools was left
> with `adb.exe` but no `AdbWinApi.dll`, so adb died with exit 53 and no message.
> They now live in durable paths. The SDK's own platform-tools was complete all
> along — prefer it over any temp copy.

---

## Connect to the Shield each session

```powershell
$adb = "C:\android-sdk\platform-tools\adb.exe"
& $adb devices
```

If the Shield isn't listed, go to **Settings → Device Preferences → Developer options → Wireless debugging** on the Shield, note the IP:port, then:

```powershell
& $adb connect <ip>:5555
```

Re-pairing is not needed — the pairing key from the first session is saved.  
The Shield's IP has been `192.168.1.8`, `192.168.1.25`, and (as of 2026-08-14)
`192.168.1.9` — the current value is shown right on the Developer options screen under
**Network debugging → Enabled on …**.

If `adb devices` shows the Shield as **`unauthorized`**, the key was revoked (the
Developer options screen has a "Revoke USB debugging authorizations" button). Nothing
can be pushed until someone accepts the "Allow debugging from this computer?" dialog
**on the TV** — it can't be done from here.

---

## Build & deploy after making code changes

```powershell
$env:ANDROID_HOME = "C:\android-sdk"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$gradle = "C:\Users\jphel\tools\gradle-8.5\bin\gradle.bat"
Set-Location "C:\Users\jphel\wifi_debug\photos-screensaver"
& $gradle assembleDebug

$adb = "C:\android-sdk\platform-tools\adb.exe"
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
```

(Gradle 8.5, not the 8.4 in `gradle/wrapper/` — the only JDK on this box is 21, and
Gradle didn't support 21 until 8.5. There's no `gradlew` script or wrapper jar here,
so the wrapper can't bootstrap itself; call gradle by path.)

The build takes ~10–30 seconds. `-r` reinstalls without losing settings.

---

## Change the Google Photos album

Option A — on the Shield: open the **Photos Screensaver** app, tap "Change Album", paste a new shared album URL.

Option B — via ADB:
```powershell
$adb = "$env:TEMP\platform-tools\platform-tools\adb.exe"
& $adb shell "am start -n com.example.photossaver/.SetupActivity"
```

The album URL must be a **public shared link** (`https://photos.app.goo.gl/...`).  
The app caches up to 50 photos and refreshes the cache once every 24 hours.

---

## Force a photo cache refresh

```powershell
$adb = "$env:TEMP\platform-tools\platform-tools\adb.exe"
& $adb shell "pm clear com.example.photossaver"
```

This wipes cached photos and stored settings. You'll need to re-enter the album URL in the app afterward.  
To clear only the photo cache without touching settings, open the app and use "Change Album" (it clears the cache before re-fetching).

---

## What each source file does

| File | Purpose |
|------|---------|
| `PhotoDreamService.kt` | The screensaver itself — layout, slideshow, clock, weather, EXIF date/location |
| `PhotoFetcher.kt` | Downloads and caches photos from the Google Photos shared album HTML |
| `AuthManager.kt` | OAuth2 device-flow token management (Google API auth, if needed later) |
| `SetupActivity.kt` | The setup UI shown when you open the app on the Shield |

---

## Common tweaks

**Change how long each photo shows** — `PhotoDreamService.kt` line ~209:
```kotlin
handler.postDelayed({ showNext() }, 12_000)  // 12 seconds
```

**Change how many photos are fetched** — `PhotoFetcher.kt` line ~31:
```kotlin
.take(50)
```

**Change the clock update interval** — `PhotoDreamService.kt` line ~167:
```kotlin
handler.postDelayed(this, 15_000)  // every 15 seconds
```

**Change weather refresh interval** — `PhotoDreamService.kt` line ~199:
```kotlin
handler.postDelayed({ fetchWeather() }, 30 * 60 * 1000L)  // every 30 min
```

---

## Notes on location & date display

- **Date** is read from the photo's EXIF `DateTimeOriginal` tag. Shows as "Month D, Year".
- **Location** is reverse-geocoded from GPS coordinates in the EXIF. Tries Android's built-in Geocoder first, falls back to Nominatim (OpenStreetMap) if that fails.
- If a photo has no GPS in its EXIF (Google Photos sometimes strips this from shared albums), both fields will be blank for that photo — this is expected.
