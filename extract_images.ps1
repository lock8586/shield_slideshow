param(
    [string]$DownloadDir = "C:\Users\jphel\Downloads",
    [string]$Target      = "Z:\Photos\GooglePhotos",
    [string]$Log         = "C:\Users\jphel\wifi_debug\extract_images_log.txt"
)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$imageExt = @('.jpg','.jpeg','.heic','.png','.gif')

# Windows/SMB reject path segments ending in space or dot — trim them.
function Clean([string]$s) { ($s -replace '[ .]+$','').Trim() }

"Image extraction started $(Get-Date)" | Out-File $Log -Encoding utf8
$zips = Get-ChildItem "$DownloadDir\takeout-20260603T231758Z-3-*.zip" | Sort-Object Name
$extracted = 0; $skipped = 0; $failed = 0

foreach ($z in $zips) {
    "[$(Get-Date -Format HH:mm:ss)] Opening $($z.Name)" | Out-File $Log -Append -Encoding utf8
    $zip = [System.IO.Compression.ZipFile]::OpenRead($z.FullName)
    foreach ($e in $zip.Entries) {
        if ([string]::IsNullOrEmpty($e.Name)) { continue }   # directory entry
        $ext = [System.IO.Path]::GetExtension($e.Name).ToLower()
        if ($imageExt -notcontains $ext) { continue }

        # Sanitize every path segment, keep folder structure
        $parts = $e.FullName -split '/' | ForEach-Object { Clean $_ } | Where-Object { $_ -ne '' }
        $rel  = $parts -join '\'
        $dest = "\\?\$Target\$rel"
        $dir  = "\\?\$Target\" + (($parts[0..($parts.Count-2)]) -join '\')

        if ([System.IO.File]::Exists($dest)) { $skipped++; continue }
        try {
            [System.IO.Directory]::CreateDirectory($dir) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $false)
            $extracted++
        } catch {
            $failed++
            "FAIL $($e.FullName): $_" | Out-File $Log -Append -Encoding utf8
        }
    }
    $zip.Dispose()
    "[$(Get-Date -Format HH:mm:ss)] Done $($z.Name) -- extracted=$extracted skipped=$skipped failed=$failed" | Out-File $Log -Append -Encoding utf8
}
"COMPLETE $(Get-Date) -- extracted=$extracted skipped=$skipped failed=$failed" | Out-File $Log -Append -Encoding utf8
