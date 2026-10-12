$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$readiness = Get-Content -LiteralPath (Join-Path $repoRoot 'packaging/release-readiness.json') -Raw | ConvertFrom-Json
$pending = @($readiness.gates.PSObject.Properties | Where-Object {
    $_.Value.passed -ne $true -or -not $_.Value.evidence -or
    -not (Test-Path -LiteralPath (Join-Path $repoRoot $_.Value.evidence) -PathType Leaf)
} | ForEach-Object { $_.Name })
if ($pending.Count) { throw "Public release blocked by unvalidated gates: $($pending -join ', '). See docs/development/production-validation.md." }
$match = [regex]::Match((Get-Content -LiteralPath (Join-Path $repoRoot 'build.gradle.kts') -Raw), 'version\s*=\s*"([0-9]+(?:\.[0-9]+){1,3})"')
if ($readiness.version -ne $match.Groups[1].Value) { throw 'Release evidence belongs to another version.' }
