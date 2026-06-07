$root = "Z:\Photos\GooglePhotos"
$exts = @('.jpg','.jpeg','.heic','.png')
$log  = "C:\Users\jphel\wifi_debug\dedupe_report.txt"

"Analyzing $root ..." | Tee-Object $log
$imgs = Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $exts -contains $_.Extension.ToLower() }
"Total images: $($imgs.Count)" | Tee-Object $log -Append

# Only files that share an exact byte size can be exact duplicates — hash just those.
$candidates = $imgs | Group-Object Length | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Group }
"Dupe candidates (shared size): $($candidates.Count)" | Tee-Object $log -Append

$seen = @{}
$dupes = New-Object System.Collections.Generic.List[string]
$reclaim = 0L
$i = 0
foreach ($f in $candidates) {
    $i++
    if ($i % 1000 -eq 0) { "  hashed $i / $($candidates.Count)" | Tee-Object $log -Append }
    try { $h = (Get-FileHash -LiteralPath $f.FullName -Algorithm MD5).Hash } catch { continue }
    $key = "$($f.Length)-$h"
    if ($seen.ContainsKey($key)) { $dupes.Add($f.FullName); $reclaim += $f.Length }
    else { $seen[$key] = $f.FullName }
}
$dupes | Out-File "C:\Users\jphel\wifi_debug\dupes_to_remove.txt" -Encoding utf8

"" | Tee-Object $log -Append
"===== DEDUP REPORT =====" | Tee-Object $log -Append
"Total images:        $($imgs.Count)" | Tee-Object $log -Append
"Exact duplicates:    $($dupes.Count)" | Tee-Object $log -Append
"Unique images kept:  $($imgs.Count - $dupes.Count)" | Tee-Object $log -Append
("Space reclaimable:   {0:N1} GB" -f ($reclaim/1GB)) | Tee-Object $log -Append
"COMPLETE" | Tee-Object $log -Append
