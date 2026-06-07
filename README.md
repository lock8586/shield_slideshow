# WiFi Debug — Session Context

## Goal
Diagnose and fix lag when streaming Stardew Valley via Sunshine (PC) → Moonlight → NVIDIA Shield TV Pro.

## PC Setup
- **Sunshine** updated to v2026.516.143833 (was 2025.924.154138)
- **Two active network adapters**: Ethernet (192.168.1.24) and Wi-Fi (192.168.1.12) — both connected simultaneously
- **NordVPN active** (OpenVPN adapter at 10.100.0.2, NordLynx at 10.5.0.2) — suspected lag contributor

## Network
- Subnet: 192.168.1.x, gateway 192.168.1.1
- **Shield TV is at 192.168.1.25** (identified by NVIDIA MAC OUI d8:13:99)

## ADB Status
- ADB platform tools downloaded to `%TEMP%\platform-tools\platform-tools\adb.exe`
- Shield ADB not yet connected — port 5555 closed
- Next step: enable **Wireless debugging** in Shield Developer Options, use `adb pair` with the code shown on screen

## Suspected Lag Causes (priority order)
1. NordVPN routing LAN traffic through VPN tunnel — test by pausing NordVPN
2. Sunshine encoder may be set to software instead of `nvenc` — check at https://localhost:47990
3. Stardew running uncapped FPS — add `-fps 60` to Steam launch options
4. Dual WiFi+Ethernet on host PC causing routing confusion

## To Resume ADB Connection (each session)

Pairing is **persistent** — no need to re-pair. Just reconnect:

```powershell
$adb = "$env:TEMP\platform-tools\platform-tools\adb.exe"

# Step 1: check if already connected (Shield IP can change)
& $adb devices

# Step 2: if not listed, get the current IP:port from Shield screen:
#   Settings → Device Preferences → Developer options → Wireless debugging
& $adb connect <ip>:<port>   # e.g. 192.168.1.8:5555

# Step 3: shell in
& $adb shell
```

> Known Shield IPs: 192.168.1.25 (old), 192.168.1.8 (current as of 2026-05-31)
