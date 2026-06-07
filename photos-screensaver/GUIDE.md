# Photos Screensaver — Maintenance Guide

## Tools & paths (one-time setup, already done)

| Tool | Path |
|------|------|
| ADB | `%TEMP%\platform-tools\platform-tools\adb.exe` |
| Gradle 8.4 | `%TEMP%\gradle-8.4\bin\gradle.bat` |
| Android SDK | `C:\android-sdk` |

---

## Connect to the Shield each session

```powershell
$adb = "$env:TEMP\platform-tools\platform-tools\adb.exe"
& $adb devices
```

If the Shield isn't listed, go to **Settings → Device Preferences → Developer options → Wireless debugging** on the Shield, note the IP:port, then:

```powershell
& $adb connect <ip>:5555
```

Re-pairing is not needed — the pairing key from the first session is saved.  
The Shield's IP has been `192.168.1.8` or `192.168.1.25` — check the TV if it changed.

---

## Build & deploy after making code changes

```powershell
$env:ANDROID_HOME = "C:\android-sdk"
$gradle = "$env:TEMP\gradle-8.4\bin\gradle.bat"
Set-Location "C:\Users\jphel\wifi_debug\photos-screensaver"
& $gradle assembleDebug

$adb = "$env:TEMP\platform-tools\platform-tools\adb.exe"
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
```

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
