$root = "Z:\Photos\GooglePhotos"
$exts = @('.jpg','.jpeg','.heic','.png')
$log  = "C:\Users\jphel\wifi_debug\dedupe_report.txt"

"Analyzing (parallel, 256KB prefilter)..." | Out-File $log
$imgs = Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $exts -contains $_.Extension.ToLower() }
"Total images: $($imgs.Count)" | Out-File $log -Append

# Only files sharing an exact byte-size can be dupes — everything else is unique.
$candidates = $imgs | Group-Object Length | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Group }
"Size-collision candidates: $($candidates.Count)" | Out-File $log -Append

$worker = {
    param($path, $size)
    try {
        $fs  = [System.IO.File]::OpenRead($path)
        $buf = New-Object byte[] 262144
        $n   = $fs.Read($buf, 0, 262144); $fs.Dispose()
        $md5 = [System.Security.Cryptography.MD5]::Create()
        $h   = ([BitConverter]::ToString($md5.ComputeHash($buf, 0, $n)) -replace '-')
        "$size`t$h`t$path"
    } catch {}
}

$pool = [runspacefactory]::CreateRunspacePool(1, 16); $pool.Open()
$lines = New-Object System.Collections.ArrayList
$batch = 4000
for ($i = 0; $i -lt $candidates.Count; $i += $batch) {
    $end   = [Math]::Min($i + $batch - 1, $candidates.Count - 1)
    $slice = $candidates[$i..$end]
    $hs = New-Object System.Collections.ArrayList
    foreach ($f in $slice) {
        $ps = [powershell]::Create(); $ps.RunspacePool = $pool
        [void]$ps.AddScript($worker).AddArgument($f.FullName).AddArgument($f.Length)
        [void]$hs.Add([pscustomobject]@{ ps = $ps; h = $ps.BeginInvoke() })
    }
    foreach ($x in $hs) { $r = $x.ps.EndInvoke($x.h); if ($r) { [void]$lines.Add([string]$r) }; $x.ps.Dispose() }
    "  hashed $([Math]::Min($i + $batch, $candidates.Count)) / $($candidates.Count)" | Out-File $log -Append
}
$pool.Close()

# Dedupe by size+hash; keep first occurrence, rest are duplicates.
$seen = @{}; $dupes = New-Object System.Collections.ArrayList; $reclaim = 0L
foreach ($line in $lines) {
    $p = $line -split "`t", 3
    $key = "$($p[0])-$($p[1])"
    if ($seen.ContainsKey($key)) { [void]$dupes.Add($p[2]); $reclaim += [int64]$p[0] }
    else { $seen[$key] = $p[2] }
}
$dupes | Out-File "C:\Users\jphel\wifi_debug\dupes_to_remove.txt" -Encoding utf8

"" | Out-File $log -Append
"===== DEDUP REPORT =====" | Out-File $log -Append
"Total images:        $($imgs.Count)" | Out-File $log -Append
"Exact duplicates:    $($dupes.Count)" | Out-File $log -Append
"Unique images kept:  $($imgs.Count - $dupes.Count)" | Out-File $log -Append
("Space reclaimable:   {0:N1} GB" -f ($reclaim / 1GB)) | Out-File $log -Append
"COMPLETE" | Out-File $log -Append
