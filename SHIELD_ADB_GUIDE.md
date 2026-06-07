# Shield TV Pro — ADB Control Guide

## Device Info
| | |
|---|---|
| Device | NVIDIA Shield TV Pro |
| IP | 192.168.1.8 |
| ADB Port | 5555 |
| Subnet | 192.168.1.x / gateway 192.168.1.1 |

## ADB Setup

ADB tools are already downloaded:
```powershell
$adb = "$env:TEMP\platform-tools\platform-tools\adb.exe"
```

### Connect
```powershell
$adb = "$env:TEMP\platform-tools\platform-tools\adb.exe"
& $adb connect 192.168.1.8:5555
& $adb devices   # confirm "device" not "offline"
```

If connection is refused, Wireless Debugging may have reset (happens after reboot):
1. On Shield: **Settings → Developer Options → Wireless Debugging**
2. Tap **Pair device with pairing code**
3. Run:
```powershell
& $adb pair 192.168.1.8:<pairing-port>   # enter 6-digit code shown on screen
& $adb connect 192.168.1.8:5555
```

---

## Reading the Screen

```powershell
# Dump UI hierarchy to XML
& $adb shell uiautomator dump /sdcard/ui.xml
& $adb pull /sdcard/ui.xml "$env:TEMP\shield_ui.xml"

# Extract text labels (run in Bash tool, not PowerShell)
grep -o 'text="[^"]*"' "$TEMP/shield_ui.xml" | grep -v 'text=""'

# Find bounds of a specific element
grep -o 'text="Settings"[^>]*bounds="[^"]*"' "$TEMP/shield_ui.xml"
```

---

## Controlling the Shield

### Tapping & Navigation
```powershell
& $adb shell input tap 960 540          # tap x y (1920x1080 screen)
& $adb shell input keyevent 20          # D-pad DOWN
& $adb shell input keyevent 19          # D-pad UP
& $adb shell input keyevent 21          # D-pad LEFT
& $adb shell input keyevent 22          # D-pad RIGHT
& $adb shell input keyevent 23          # D-pad CENTER / OK
& $adb shell input keyevent 4           # BACK
& $adb shell input keyevent 3           # HOME
& $adb shell input keyevent 224         # WAKE SCREEN
& $adb shell input keyevent 223         # SLEEP SCREEN
```

### Scrolling
```powershell
& $adb shell input swipe 960 800 960 400 500   # swipe up (scroll down)
& $adb shell input swipe 960 400 960 800 500   # swipe down (scroll up)
```

### Typing text
```powershell
& $adb shell input text "hello"         # type into focused field
```

### Launching apps & activities
```powershell
& $adb shell am start -n "com.android.tv.settings/.MainSettings"
& $adb shell am start -a android.intent.action.VIEW -d "market://details?id=PACKAGE"
& $adb shell am force-stop com.example.myapp
```

---

## Navigating Settings

Shield TV settings are fragment-based — most can't be deep-linked directly.
Navigate by tapping items using their bounds from the UI dump.

### Common paths
| Destination | Path |
|---|---|
| Screensaver | Settings → Device Preferences → Screen Saver (scroll down) |
| Developer Options | Settings → Device Preferences → scroll down |
| Display & Sound | Settings → Device Preferences → Display & Sound |
| Apps | Settings → Apps |

---

## Installing Apps

### Simple APK
```powershell
& $adb install -r path\to\app.apk
```

### Split APK bundle (.apkm from APKMirror)
```powershell
# Extract the .apkm (it's a zip)
Expand-Archive app.apkm -DestinationPath "$env:TEMP\apkm" -Force

# Install with install-multiple
& $adb install-multiple -r "$env:TEMP\apkm\base.apk" "$env:TEMP\apkm\split_config.en.apk" "$env:TEMP\apkm\split_config.tvdpi.apk"
```

### Uninstall
```powershell
& $adb uninstall com.example.myapp
```

---

## Writing App Data (debug builds only)

```powershell
# Push a file to a location ADB can write, then copy into app sandbox
& $adb push local_file.xml /data/local/tmp/file.xml
& $adb shell "run-as com.example.myapp mkdir -p /data/data/com.example.myapp/shared_prefs"
& $adb shell "run-as com.example.myapp cp /data/local/tmp/file.xml /data/data/com.example.myapp/shared_prefs/file.xml"

# Read a file back
& $adb shell "run-as com.example.myapp cat /data/data/com.example.myapp/shared_prefs/file.xml"
```

---

## Screensaver Control

```powershell
# Set active screensaver
& $adb shell settings put secure screensaver_components "com.example.myapp/.MyDreamService"
& $adb shell settings put secure screensaver_enabled 1

# Check current screensaver
& $adb shell settings get secure screensaver_components

# Start screensaver now (via Settings UI — no direct ADB trigger on Shield)
# Navigate to: Settings → Device Preferences → Screen Saver → Start now
```

---

## Useful Diagnostics

```powershell
# List installed packages
& $adb shell pm list packages

# Check what's on screen (current activity)
& $adb shell dumpsys activity activities | Select-String "Hist #0"

# Logcat (filter by app)
& $adb shell logcat -d | Select-String "myapp"

# Clear logcat
& $adb shell logcat -c

# Check network interfaces
& $adb shell ip addr

# List available screensaver services
& $adb shell "cmd package query-services --components -a android.service.dreams.DreamService"

# Check ADB shell user
& $adb shell id
```

---

## Photos Screensaver App

Installed package: `com.example.photossaver`
Source: `C:\Users\jphel\wifi_debug\photos-screensaver\`

### Update album URL
```powershell
$xml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="album_url">PASTE_ALBUM_URL_HERE</string>
</map>
"@
$xml | Out-File "$env:TEMP\config.xml" -Encoding utf8 -NoNewline
& $adb push "$env:TEMP\config.xml" /data/local/tmp/config.xml
& $adb shell "run-as com.example.photossaver cp /data/local/tmp/config.xml /data/data/com.example.photossaver/shared_prefs/config.xml"
& $adb shell am force-stop com.example.photossaver
```

### Clear photo cache (forces re-fetch)
```powershell
& $adb shell "run-as com.example.photossaver rm -rf /data/data/com.example.photossaver/cache/photos"
```

### Rebuild & reinstall
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:ANDROID_HOME = "C:\android-sdk"
$gradle = "$env:TEMP\gradle-8.4\bin\gradle.bat"
Set-Location "C:\Users\jphel\wifi_debug\photos-screensaver"
& $gradle assembleDebug
& "$env:TEMP\platform-tools\platform-tools\adb.exe" install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```
