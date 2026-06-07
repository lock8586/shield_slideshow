$root = "Z:\Photos\GooglePhotos"; $exts = @('.jpg','.jpeg','.heic','.png')
$dupes = Get-Content "C:\Users\jphel\wifi_debug\dupes_to_remove.txt" | Where-Object { $_ }
$imgs = Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $exts -contains $_.Extension.ToLower() }
$bySize = @{}
foreach ($f in $imgs) {
    if (-not $bySize.ContainsKey($f.Length)) { $bySize[$f.Length] = New-Object System.Collections.ArrayList }
    [void]$bySize[$f.Length].Add($f.FullName)
}
$sample = $dupes | Get-Random -Count 25
$confirmed = 0; $notconfirmed = 0
foreach ($d in $sample) {
    $sz = (Get-Item -LiteralPath $d).Length
    $dh = (Get-FileHash -LiteralPath $d -Algorithm MD5).Hash
    $twin = $false
    foreach ($other in $bySize[$sz]) {
        if ($other -eq $d) { continue }
        if ((Get-FileHash -LiteralPath $other -Algorithm MD5).Hash -eq $dh) { $twin = $true; break }
    }
    if ($twin) { $confirmed++ } else { $notconfirmed++; Write-Host "NOT CONFIRMED: $d" }
}
Write-Host "Confirmed genuine byte-identical duplicates: $confirmed / 25"
Write-Host "Not confirmed: $notconfirmed"
