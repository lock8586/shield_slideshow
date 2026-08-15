# Project: wifi_debug / Photos Screensaver

Home-network + NVIDIA Shield TV photo-screensaver project. Photos live on a Synology
DS223 NAS and are shown on the Shield via a custom Android dream service
(`photos-screensaver/`).

## Working agreements

- **Always run long jobs as tracked background tasks and proactively ping when they finish.**
  Use the tool's `run_in_background: true` so the harness notifies on completion — then
  message the user with the result. Do NOT launch long jobs with detached PowerShell
  `Start-Process`: they run unmonitored, no completion notification fires, and the user is
  left having to ask "are we done yet?" This already happened once during a Takeout
  extraction — don't repeat it. If a job genuinely must be detached, say so up front and
  state how/when I'll check back.

## Key infra (verify before trusting — IPs can change)

- NAS: `192.168.1.43`, SMB share `Media` mounted as `Z:`, login user `welps`.
- Shield TV: `192.168.1.12` (eth0 MAC `3C:6D:66:85:C1:79` — wired, not Wi-Fi), ADB over `:5555`
  (`%TEMP%\platform-tools\platform-tools\adb.exe`). IP has drifted before (.15→.12); run
  `connect-shield.ps1` to connect by MAC if the IP moved. Router DHCP reservation for that MAC
  is the durable fix. Build flag for bash: set `ANDROID_HOME=/c/android-sdk` or Gradle won't find the SDK.
- Build: `%ANDROID_HOME%=C:\android-sdk`, Gradle at `%TEMP%\gradle-8.4\bin\gradle.bat`.
- The Google Photos Library API is dead for this use case — see memory `photos-screensaver-api-dead`.
