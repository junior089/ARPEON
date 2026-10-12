param([Parameter(Mandatory)][string]$Package)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath($Package)
$rootItem = Get-Item -LiteralPath $root -Force
if (-not $rootItem.PSIsContainer -or ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Package root must be a real directory.' }
$manifest = Get-Item -LiteralPath (Join-Path $root 'FILES-SHA256.json') -Force
if ($manifest.Length -gt 16MB -or ($manifest.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Package inventory is unsafe.' }
$files = Get-Content -LiteralPath $manifest.FullName -Raw | ConvertFrom-Json
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in $files) {
    if ($entry.path -notmatch '^[^:\\]+$' -or ($entry.path -split '/') -contains '..' -or
        [IO.Path]::IsPathRooted($entry.path) -or $entry.sha256 -notmatch '^[a-f0-9]{64}$' -or
        -not $seen.Add($entry.path)) { throw 'Invalid or duplicate package inventory entry.' }
    $path = [IO.Path]::GetFullPath((Join-Path $root $entry.path))
    if (-not $path.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Manifest path escapes the package.' }
    $parent = [IO.Path]::GetDirectoryName($path)
    while ($parent -ne $root) {
        if ((Get-Item -LiteralPath $parent -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Package directory is a reparse point.' }
        $parent = [IO.Path]::GetDirectoryName($parent)
    }
    $file = Get-Item -LiteralPath $path -Force
    if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Package file is a reparse point.' }
    if ($file.Length -ne $entry.size -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "Package integrity failed: $($entry.path)" }
}
Write-Host "Verified $($files.Count) packaged files. A checksum is not a publisher signature."
