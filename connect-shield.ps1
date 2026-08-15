<#
  connect-shield.ps1 — connect ADB to the NVIDIA Shield even if its IP has drifted.

  Tries the last-known IP first; if that fails, finds the Shield on the LAN by its
  Ethernet MAC and connects to whatever IP it currently holds. Optionally installs
  the screensaver APK and launches the menu.

  Usage:
    .\connect-shield.ps1                # connect only
    .\connect-shield.ps1 -Install       # connect, install latest debug APK, launch menu
#>
param([switch]$Install)

$ErrorActionPreference = 'Stop'

# Not the %TEMP% copy — Windows temp cleanup strips its DLLs and adb then dies
# with exit 53 and no output at all. The SDK's own platform-tools is complete.
$Adb       = 'C:\android-sdk\platform-tools\adb.exe'
$Mac       = '3c-6d-66-85-c1-79'          # Shield eth0 MAC
$Preferred = '192.168.1.9'                # last-known IP (was .12 until 2026-08-14)
$Subnet    = '192.168.1'
$Apk       = Join-Path $PSScriptRoot 'photos-screensaver\app\build\outputs\apk\debug\app-debug.apk'

function Test-AdbDevice($ip) {
    & $Adb connect "${ip}:5555" | Out-Null
    Start-Sleep -Milliseconds 600
    $line = (& $Adb devices) | Where-Object { $_ -match [regex]::Escape("${ip}:5555") }
    return ($line -match '\bdevice\b')   # 'device' = ready; 'unauthorized'/'offline' = not
}

function Find-ByMac {
    Write-Host "Scanning $Subnet.0/24 for the Shield ($Mac)..." -ForegroundColor Cyan
    # Warm the ARP cache with a quick parallel ping sweep, then read the table.
    1..254 | ForEach-Object {
        Start-Job -ScriptBlock { param($t) Test-Connection -ComputerName $t -Count 1 -Quiet -TimeoutSeconds 1 } -ArgumentList "$Subnet.$_"
    } | Wait-Job -Timeout 20 | Out-Null
    Get-Job | Remove-Job -Force
    $hit = (arp -a) | Where-Object { $_ -match $Mac }
    if ($hit -match "$Subnet\.\d+") { return $Matches[0] }
    return $null
}

# 1) Try the preferred IP.
$ip = $Preferred
if (-not (Test-AdbDevice $ip)) {
    # 2) Fall back to MAC-based discovery.
    $found = Find-ByMac
    if (-not $found) {
        Write-Host "Could not find the Shield. Is it powered on and Network debugging enabled?" -ForegroundColor Red
        exit 1
    }
    Write-Host "Found Shield at $found" -ForegroundColor Green
    $ip = $found
    if (-not (Test-AdbDevice $ip)) {
        Write-Host "Connected to $ip but it's not authorized — accept the prompt on the TV, then re-run." -ForegroundColor Yellow
        exit 1
    }
}

Write-Host "Shield ready at ${ip}:5555" -ForegroundColor Green
if ($ip -ne $Preferred) {
    Write-Host "NOTE: IP changed from $Preferred to $ip — update the reservation or this script's `$Preferred." -ForegroundColor Yellow
}

if ($Install) {
    if (-not (Test-Path $Apk)) { Write-Host "APK not found at $Apk — build it first." -ForegroundColor Red; exit 1 }
    Write-Host "Installing $Apk ..." -ForegroundColor Cyan
    & $Adb -s "${ip}:5555" install -r $Apk
    & $Adb -s "${ip}:5555" shell am start -n com.example.photossaver/.SetupActivity | Out-Null
    Write-Host "Installed and launched the menu." -ForegroundColor Green
}
