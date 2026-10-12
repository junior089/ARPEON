param([Parameter(Mandatory)][string]$Package, [Parameter(Mandatory)][string]$Version, [switch]$PublicDistributionApproved)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath($Package)
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$commit = & git -C $repoRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot record source revision.' }
$dirty = @(& git -C $repoRoot status --porcelain).Count -gt 0
@{
    version = $Version; sourceRevision = $commit; sourceDirty = $dirty
    publicDistributionApproved = [bool]$PublicDistributionApproved
    platform = 'windows-x64'; builtAt = [DateTimeOffset]::UtcNow.ToString('o')
    java = ((& (Join-Path $env:JAVA_HOME 'bin/java.exe') --version) -join "`n")
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'BUILD-INFO.json') -Encoding utf8
$files = @(Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object Name -ne 'FILES-SHA256.json' | Sort-Object FullName | ForEach-Object {
    if ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Package contains a reparse point.' }
    @{ path = [IO.Path]::GetRelativePath($root, $_.FullName).Replace('\','/'); size = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
ConvertTo-Json -InputObject $files -Depth 3 | Set-Content -LiteralPath (Join-Path $root 'FILES-SHA256.json') -Encoding utf8
