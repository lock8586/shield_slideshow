<#
  gen_me_ellen.ps1 — build manifest_me_ellen.txt for the screensaver's "Me & Ellen" theme.

  Queries Synology Photos (personal space) for every photo Ellen (person 294) is in, keeps
  the ones that ALSO contain Josh (person 293), resolves each to its library-relative path,
  and writes "relpath|YYYYMMDD" lines — the same format as manifest.txt. The output must end
  up in /volume1/homes/welps/Photos so the :8080 server hands it to the Shield.

  Output is written locally next to this script; deployment to the NAS is a separate step.
#>
param(
  [int]$PersonA = 294,   # Ellen
  [int]$PersonB = 293,   # Josh (me)
  [string]$Out    = (Join-Path $PSScriptRoot 'manifest_me_ellen.txt'),
  [string]$Deploy = '\\192.168.1.43\home\Photos\manifest_me_ellen.txt'  # served at :8080
)
$ErrorActionPreference='Stop'
# Log each (unattended) run so failures are visible afterward.
try { Start-Transcript -Path (Join-Path $PSScriptRoot 'gen_me_ellen.log') -Append -ErrorAction SilentlyContinue | Out-Null } catch {}
Write-Host ("==== run @ {0} ====" -f (Get-Date))
$base='http://192.168.1.43:5000/webapi/entry.cgi'
$pwFile='C:\Users\jphel\OneDrive\Documents\nas_pw.txt'

# --- credentials (handle OneDrive on-demand: retry until the real file lands) ---
$map=$null
for ($i=1; $i -le 20 -and -not $map; $i++) {
  $raw = Get-Content $pwFile -Raw -ErrorAction SilentlyContinue
  if ($raw -and $raw.Length -gt 8) {
    $m=@{}; foreach ($l in ($raw -split "`r?`n")) { if ($l -match '^\s*([^:]+):\s*(.*)$') { $m[$Matches[1].Trim().ToLower()]=$Matches[2].Trim() } }
    $u=$m['username']; if(-not $u){$u=$m['user']}
    $p=$m['pw']; if(-not $p){$p=$m['pass']}; if(-not $p){$p=$m['password']}
    if ($u -and $p) { $map=@{u=$u;p=$p} }
  }
  if (-not $map) { Start-Sleep -Milliseconds 600 }
}
if (-not $map) { throw "Could not read NAS credentials from $pwFile (OneDrive not hydrated - pin the file)." }

$sid=(Invoke-RestMethod -Method Post -Uri $base -Body @{api='SYNO.API.Auth';version='7';method='login';account=$map.u;passwd=$map.p;format='sid'}).data.sid
if (-not $sid) { throw "Login failed." }

# Reuse the dates already computed by gen_manifest.py (EXIF/filename/folder) so age-bucketing
# matches the rest of the app. Map relpath -> YYYYMMDD from the live manifest.txt.
$dateMap=@{}
try {
  foreach ($l in ((Invoke-WebRequest -Uri 'http://192.168.1.43:8080/manifest.txt' -UseBasicParsing -TimeoutSec 20).Content -split "`n")) {
    $bar=$l.IndexOf('|'); if ($bar -ge 0) { $dateMap[$l.Substring(0,$bar)] = $l.Substring($bar+1).Trim() }
  }
  Write-Host "Loaded $($dateMap.Count) dated paths from manifest.txt"
} catch { Write-Host "WARN: couldn't load manifest.txt for dates ($($_.Exception.Message)); falling back to API time" }

Write-Host "Logged in. Scanning person $PersonA's photos for co-appearances with $PersonB..."

try {
  $folderCache=@{}
  function Get-Prefix($fid) {
    if ($folderCache.ContainsKey($fid)) { return $folderCache[$fid] }
    $f=Invoke-RestMethod -Method Get -Uri $base -Body @{api='SYNO.Foto.Browse.Folder';version='1';method='get';id="$fid";_sid=$sid}
    $name=[string]$f.data.folder.name
    $prefix=$name.Trim('/')
    if ($prefix) { $prefix="$prefix/" }
    $folderCache[$fid]=$prefix
    return $prefix
  }

  $lines=New-Object System.Collections.Generic.List[string]
  $offset=0; $page=500; $scanned=0
  while ($true) {
    $r=Invoke-RestMethod -Method Get -Uri $base -Body @{api='SYNO.Foto.Browse.Item';version='1';method='list';offset="$offset";limit="$page";person_id="$PersonA";additional='["person"]';_sid=$sid}
    $batch=@($r.data.list)
    if ($batch.Count -eq 0) { break }
    foreach ($it in $batch) {
      $scanned++
      $ids = @($it.additional.person | ForEach-Object { $_.id })
      if ($ids -contains $PersonB) {
        $prefix = Get-Prefix $it.folder_id
        $rel = ($prefix + $it.filename) -replace '\\','/'
        $ymd = if ($dateMap.ContainsKey($rel)) { $dateMap[$rel] }
               elseif ($it.time -and $it.time -gt 0) { [DateTimeOffset]::FromUnixTimeSeconds([int64]$it.time).LocalDateTime.ToString('yyyyMMdd') }
               else { '00000000' }
        $lines.Add("$rel|$ymd")
      }
    }
    $offset += $batch.Count
    Write-Host ("  scanned $scanned, matched $($lines.Count)...")
    if ($batch.Count -lt $page) { break }
  }

  Set-Content -Path $Out -Value ($lines -join "`n") -Encoding UTF8 -NoNewline
  Write-Host ("DONE: $($lines.Count) photos with both -> $Out")

  # Deploy to the NAS so the :8080 server hands it to the Shield.
  if ($Deploy) {
    Copy-Item $Out $Deploy -Force
    Write-Host ("Deployed -> $Deploy")
  }
}
finally {
  Invoke-RestMethod -Method Get -Uri $base -Body @{api='SYNO.API.Auth';version='7';method='logout';_sid=$sid} | Out-Null
  try { Stop-Transcript -ErrorAction SilentlyContinue | Out-Null } catch {}
}
