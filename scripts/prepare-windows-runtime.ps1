param([string]$CacheDirectory = '', [switch]$PrepareInstallerTools)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:OS -ne 'Windows_NT') { throw 'Windows x64 build host required.' }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lock = Get-Content -LiteralPath (Join-Path $repoRoot 'packaging/native-lock.json') -Raw | ConvertFrom-Json
$cacheRoot = if ($CacheDirectory) { [IO.Path]::GetFullPath($CacheDirectory) } else { Join-Path $repoRoot '.tools/production' }
New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
$resourceRoot = Join-Path $repoRoot 'apps/desktop/build/native-resources'
New-Item -ItemType Directory -Path $resourceRoot -Force | Out-Null

function Get-VerifiedArtifact([string]$Url, [string]$Sha256, [string]$Name) {
    if (([Uri]$Url).Scheme -ne 'https') { throw 'Native artifacts require HTTPS.' }
    $path = Join-Path $cacheRoot $Name
    if (-not (Test-Path -LiteralPath $path)) {
        $temporary = "$path.download-$([Guid]::NewGuid().ToString('N'))"
        try {
            Invoke-WebRequest -Uri $Url -OutFile $temporary -TimeoutSec 600
            if ((Get-Item -LiteralPath $temporary).Length -gt 256MB) { throw 'Artifact exceeds 256 MiB.' }
            if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256) { throw "Integrity check failed: $Name" }
            Move-Item -LiteralPath $temporary -Destination $path
        } finally {
            if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
        }
    }
    if ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Artifact cache must not contain reparse points.' }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256) { throw "Cached artifact failed integrity check: $Name" }
    return $path
}

$audiverisMsi = Get-VerifiedArtifact $lock.audiveris.url $lock.audiveris.sha256 'Audiveris-5.11.0-windowsConsole-x86_64.msi'
$source = Get-VerifiedArtifact $lock.audiveris.sourceUrl $lock.audiveris.sourceSha256 'audiveris-5.11.0-source.zip'
$audiverisRoot = Join-Path $resourceRoot 'audiveris-5.11.0'
if (-not (Test-Path -LiteralPath (Join-Path $audiverisRoot 'Audiveris.exe'))) {
    $stage = Join-Path $resourceRoot "audiveris-extraction-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $stage | Out-Null
    # Administrative extraction is confined to the build host; MSI is never executed by ARPEON.
    $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/a', "`"$audiverisMsi`"", '/qn', '/norestart', "TARGETDIR=`"$stage`"") -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Audiveris build extraction failed: $($process.ExitCode). Inspect $stage" }
    $executables = @(Get-ChildItem -LiteralPath $stage -Recurse -File -Filter Audiveris.exe)
    if ($executables.Count -ne 1) { throw 'Upstream Audiveris package layout changed.' }
    $extracted = [IO.Path]::GetFullPath($executables[0].DirectoryName)
    $expectedParent = [IO.Path]::GetFullPath($stage) + [IO.Path]::DirectorySeparatorChar
    if (-not $extracted.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase)) { throw 'Extraction escaped the staging directory.' }
    Move-Item -LiteralPath $extracted -Destination $audiverisRoot
}
if (-not (Test-Path -LiteralPath (Join-Path $audiverisRoot 'runtime/bin/java.exe'))) { throw 'Audiveris runtime missing.' }
Copy-Item -LiteralPath $source -Destination (Join-Path $audiverisRoot 'source.zip') -Force
$upstreamSource = [IO.Compression.ZipFile]::OpenRead($source)
try {
    $license = $upstreamSource.GetEntry('audiveris-5.11.0/LICENSE')
    if ($null -eq $license) { throw 'Audiveris license missing.' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($license, (Join-Path $audiverisRoot 'LICENSE'), $true)
} finally { $upstreamSource.Dispose() }

# Security-only dependency replacements; the Audiveris application JAR stays unmodified.
$patchSources = Join-Path $audiverisRoot 'dependency-sources'
New-Item -ItemType Directory -Path $patchSources -Force | Out-Null
$cfgPath = Join-Path $audiverisRoot 'app/Audiveris.cfg'
$cfg = [IO.File]::ReadAllText($cfgPath)
foreach ($patch in $lock.audiverisPatches) {
    foreach ($file in $patch.files) {
        $verified = Get-VerifiedArtifact $file.url $file.sha256 $file.name
        $binary = $file.name.EndsWith('.jar') -and -not $file.name.EndsWith('-sources.jar')
        $destination = if ($binary) { Join-Path $audiverisRoot "app/$($file.name)" } else { Join-Path $patchSources $file.name }
        Copy-Item -LiteralPath $verified -Destination $destination -Force
        if ($binary) {
            if (-not ($cfg.Contains($patch.oldFilename) -or $cfg.Contains($file.name))) { throw "Audiveris classpath changed: $($patch.artifact)" }
            $cfg = $cfg.Replace($patch.oldFilename, $file.name)
            $oldJar = Join-Path $audiverisRoot "app/$($patch.oldFilename)"
            if (Test-Path -LiteralPath $oldJar) { Remove-Item -LiteralPath $oldJar }
        }
    }
}
$cfg = $cfg.Replace('java-options=-Xmx8G', 'java-options=-Xmx2G')
# The official classpath already loads commons-io 2.16.1 first. Remove the vulnerable duplicate.
$cfg = [regex]::Replace($cfg, '(?m)^app\.classpath=\$APPDIR\\org\.apache\.commons\.io-2\.4\.jar\r?\n', '')
$duplicateIo = Join-Path $audiverisRoot 'app/org.apache.commons.io-2.4.jar'
if (Test-Path -LiteralPath $duplicateIo) { Remove-Item -LiteralPath $duplicateIo }
[IO.File]::WriteAllText($cfgPath, $cfg, [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $repoRoot 'packaging/native-lock.json') -Destination (Join-Path $audiverisRoot 'arpeon-runtime-lock.json') -Force
$tessdataRoot = Join-Path $audiverisRoot 'tessdata'
New-Item -ItemType Directory -Path $tessdataRoot -Force | Out-Null
foreach ($file in $lock.tessdata.files) {
    $verified = Get-VerifiedArtifact $file.url $file.sha256 "tessdata-$($file.name)"
    Copy-Item -LiteralPath $verified -Destination (Join-Path $tessdataRoot $file.name) -Force
}

$sparkleArchive = Get-VerifiedArtifact $lock.winsparkle.url $lock.winsparkle.sha256 'WinSparkle-0.9.4.zip'
$updaterRoot = Join-Path $resourceRoot 'updater'
New-Item -ItemType Directory -Path $updaterRoot -Force | Out-Null
$sparkleZip = [IO.Compression.ZipFile]::OpenRead($sparkleArchive)
try {
    foreach ($name in @('x64/Release/WinSparkle.dll', 'COPYING', 'COPYING.expat', 'AUTHORS', 'include/winsparkle.h')) {
        $entry = $sparkleZip.GetEntry("WinSparkle-0.9.4/$name")
        if ($null -eq $entry) { throw "Missing WinSparkle file: $name" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $updaterRoot ([IO.Path]::GetFileName($name))), $true)
    }
} finally { $sparkleZip.Dispose() }
$sparkleZip = [IO.Compression.ZipFile]::OpenRead($sparkleArchive)
try {
    $buildTools = Join-Path $resourceRoot 'build-tools'
    New-Item -ItemType Directory -Path $buildTools -Force | Out-Null
    $entry = $sparkleZip.GetEntry('WinSparkle-0.9.4/bin/winsparkle-tool.exe')
    if ($null -eq $entry) { throw 'WinSparkle signing tool missing.' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $buildTools 'winsparkle-tool.exe'), $true)
} finally { $sparkleZip.Dispose() }

if ($PrepareInstallerTools) {
    $wixZip = Get-VerifiedArtifact $lock.wix.url $lock.wix.sha256 'wix314-binaries.zip'
    $wixRoot = Join-Path $cacheRoot 'wix314'
    if (-not (Test-Path -LiteralPath (Join-Path $wixRoot 'candle.exe'))) { Expand-Archive -LiteralPath $wixZip -DestinationPath $wixRoot }
    Write-Host "WiX build tools: $wixRoot"
}
Write-Host "Bundled Audiveris: $audiverisRoot"
Write-Host "Bundled WinSparkle: $updaterRoot"
