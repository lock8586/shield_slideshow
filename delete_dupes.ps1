$list = Get-Content "C:\Users\jphel\wifi_debug\dupes_to_remove.txt" | Where-Object { $_ }
$log  = "C:\Users\jphel\wifi_debug\delete_report.txt"
"Deleting $($list.Count) duplicate files..." | Out-File $log

$worker = {
    param($path)
    try { [System.IO.File]::Delete("\\?\$path"); 1 } catch { 0 }
}

$pool = [runspacefactory]::CreateRunspacePool(1, 16); $pool.Open()
$deleted = 0; $failed = 0; $batch = 4000
for ($i = 0; $i -lt $list.Count; $i += $batch) {
    $end   = [Math]::Min($i + $batch - 1, $list.Count - 1)
    $slice = $list[$i..$end]
    $hs = New-Object System.Collections.ArrayList
    foreach ($p in $slice) {
        $ps = [powershell]::Create(); $ps.RunspacePool = $pool
        [void]$ps.AddScript($worker).AddArgument($p)
        [void]$hs.Add([pscustomobject]@{ ps = $ps; h = $ps.BeginInvoke() })
    }
    foreach ($x in $hs) {
        $r = @($x.ps.EndInvoke($x.h))
        if ($r.Count -gt 0 -and $r[-1] -eq 1) { $deleted++ } else { $failed++ }
        $x.ps.Dispose()
    }
    "  deleted $deleted, failed $failed  ($([Math]::Min($i + $batch, $list.Count))/$($list.Count))" | Out-File $log -Append
}
$pool.Close()

"" | Out-File $log -Append
"===== DELETE REPORT =====" | Out-File $log -Append
"Requested: $($list.Count)" | Out-File $log -Append
"Deleted:   $deleted" | Out-File $log -Append
"Failed:    $failed" | Out-File $log -Append
"COMPLETE" | Out-File $log -Append
