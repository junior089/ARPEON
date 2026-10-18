param(
    [Parameter(Mandatory)][string]$ApplicationImage,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidatePattern('^[a-z0-9-]{1,24}$')][string]$TestRunId = 'recovery'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath($OutputDirectory)
$allowed = [IO.Path]::GetFullPath((Join-Path $repoRoot 'apps/desktop/build')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $output)) { throw 'Use a new recovery fixture directory inside apps/desktop/build.' }
if (-not (Test-Path -LiteralPath (Join-Path $ApplicationImage 'ARPEON.exe') -PathType Leaf)) { throw 'A verified application image is required.' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$fixture = Join-Path $output 'fixture'
New-Item -ItemType Directory -Path $fixture | Out-Null
Copy-Item -LiteralPath (Join-Path $ApplicationImage 'ARPEON.exe') -Destination $fixture
function Write-Fixture([string]$Text) {
    [IO.File]::WriteAllText((Join-Path $fixture 'payload.txt'), $Text)
    $entries = @(Get-ChildItem -LiteralPath $fixture -File | Where-Object Name -ne 'FILES-SHA256.json' | ForEach-Object {
        @{ path = $_.Name; size = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant() }
    })
    ConvertTo-Json -InputObject $entries | Set-Content -LiteralPath (Join-Path $fixture 'FILES-SHA256.json') -Encoding utf8
}
function Get-StableGuid([string]$Value) {
    $hash = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes("org.arpeon.windows/$Value"))
    return [Guid]::new([byte[]]$hash[0..15]).ToString('B').ToUpperInvariant()
}
Write-Fixture 'Previous release payload, expected after automatic recovery.'
$previousInventory = Join-Path $output 'previous-inventory.json'
Copy-Item -LiteralPath (Join-Path $fixture 'FILES-SHA256.json') -Destination $previousInventory
& (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -Package $fixture -Version 0.1.1 -OutputDirectory (Join-Path $output 'base') -TestIdentity -TestRunId $TestRunId -SkipBootstrapper
Write-Fixture 'Fault-injected update payload. This file must never remain installed.'
& (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -Package $fixture -Version 0.1.2 -OutputDirectory (Join-Path $output 'upgrade') -TestIdentity -TestRunId $TestRunId -SkipBootstrapper
$baseMsi = Join-Path $output 'base/ARPEON-0.1.1.msi'
$upgradeMsi = Join-Path $output 'upgrade/ARPEON-0.1.2.msi'
$failingMsi = Join-Path $output 'failing-upgrade.msi'
& (Join-Path $PSScriptRoot 'new-failing-test-msi.ps1') -Installer $upgradeMsi -Output $failingMsi
$payload = Join-Path $output 'payload.msi'
Copy-Item -LiteralPath $failingMsi -Destination $payload
$registryRoot = "Software\ARPEON\InstallerTest\$TestRunId"
$productName = "ARPEON Lifecycle Test $TestRunId"
$upgradeCode = Get-StableGuid "test-upgrade/$TestRunId"
$base = @{ ProductVersion='0.1.1'; ProductCode=(Get-StableGuid "test-product/$TestRunId/0.1.1"); UpgradeCode=$upgradeCode; ProductName=$productName; InstallFolder=$productName; RegistryRoot=$registryRoot }
$current = @{ ProductVersion='0.1.2'; ProductCode=(Get-StableGuid "test-product/$TestRunId/0.1.2"); UpgradeCode=$upgradeCode; ProductName=$productName; InstallFolder=$productName; RegistryRoot=$registryRoot }
$target = Join-Path $output 'installed-previous'
$baseInstallLog = Join-Path $output 'install-base.log'
$baseArgs = @('/i',('"{0}"' -f $baseMsi),'/qn','/norestart','/l*v',('"{0}"' -f $baseInstallLog),'INSTALLDESKTOPSHORTCUT=0','INSTALLMENUSHORTCUT=0',('INSTALLDIR="{0}"' -f $target))
Write-Host "Installing previous fixture: $target"
$baseProcess = Start-Process msiexec.exe -ArgumentList $baseArgs -WindowStyle Hidden -Wait -PassThru
try { if ($baseProcess.ExitCode -ne 0) { throw "Base fixture install failed with $($baseProcess.ExitCode). See $baseInstallLog." } } finally { $baseProcess.Dispose() }
$registryPath = 'HKCU:\' + $base.RegistryRoot + '\Installation'
$registeredCode = [string](Get-ItemProperty -LiteralPath $registryPath).ProductCode
if ($registeredCode -ne $base.ProductCode) { throw 'Base fixture product registration does not match its MSI.' }
$oldMsiHash = (Get-FileHash -LiteralPath $baseMsi -Algorithm SHA256).Hash.ToLowerInvariant()
$oldMsiSize = (Get-Item -LiteralPath $baseMsi).Length
$oldInfo = [ordered]@{
    Version=$base.ProductVersion; ProductName=$base.ProductName; ProductCode=$base.ProductCode; UpgradeCode=$base.UpgradeCode
    RegistryRoot=$base.RegistryRoot; InstallFolder=$base.InstallFolder; MsiHash=$oldMsiHash; MsiSize=$oldMsiSize
    InstalledSize=[long]((Get-Content -LiteralPath (Join-Path $fixture 'FILES-SHA256.json') -Raw | ConvertFrom-Json | Measure-Object -Property size -Sum).Sum + (Get-Item -LiteralPath (Join-Path $fixture 'FILES-SHA256.json')).Length)
    HasMaintenance=$true
}
$cacheRoot = $base.RegistryRoot -replace '^Software\\',''
$oldCache = Join-Path (Join-Path $env:LOCALAPPDATA $cacheRoot) ('InstallerCache\' + $base.ProductCode)
New-Item -ItemType Directory -Path $oldCache -Force | Out-Null
Copy-Item -LiteralPath $baseMsi -Destination (Join-Path $oldCache 'ARPEON.msi')
$oldInfo | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $oldCache 'bundle.json') -Encoding utf8
$inventoryFile = Join-Path $fixture 'FILES-SHA256.json'
$currentInfo = [ordered]@{
    Version=$current.ProductVersion; ProductName=$current.ProductName; ProductCode=$current.ProductCode; UpgradeCode=$current.UpgradeCode
    RegistryRoot=$current.RegistryRoot; InstallFolder=$current.InstallFolder
    MsiHash=(Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash.ToLowerInvariant(); MsiSize=(Get-Item -LiteralPath $payload).Length
    InstalledSize=[long]((Get-Content -LiteralPath $inventoryFile -Raw | ConvertFrom-Json | Measure-Object -Property size -Sum).Sum + (Get-Item -LiteralPath $inventoryFile).Length)
    HasMaintenance=$true
}
$bundleFile = Join-Path $output 'bundle.json'; $currentInfo | ConvertTo-Json | Set-Content -LiteralPath $bundleFile -Encoding utf8
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$sources = @((Join-Path $repoRoot 'packaging/windows/bootstrapper/InstallerEngine.cs'),(Join-Path $repoRoot 'packaging/windows/bootstrapper/InstallerRecoveryTests.cs'))
$compilerArgs = @('/nologo','/target:exe','/platform:x64','/main:Arpeon.Setup.InstallerRecoveryTests',("/reference:{0}" -f (Join-Path $framework 'System.dll')),("/reference:{0}" -f (Join-Path $framework 'System.Core.dll')),("/reference:{0}" -f (Join-Path $framework 'System.Web.Extensions.dll')),("/resource:{0},bundle.json" -f $bundleFile),("/resource:{0},inventory.json" -f $inventoryFile),("/resource:{0},previous-inventory.json" -f $previousInventory),("/resource:{0},payload.msi" -f $payload),("/out:{0}" -f (Join-Path $output 'InstallerRecoveryTests.exe'))) + $sources
& (Join-Path $framework 'csc.exe') @compilerArgs
if ($LASTEXITCODE -ne 0) { throw 'Recovery test harness compilation failed.' }
$test = Start-Process -FilePath (Join-Path $output 'InstallerRecoveryTests.exe') -ArgumentList ('"{0}"' -f $output) -Wait -PassThru
try { if ($test.ExitCode -ne 0) { Get-Content -LiteralPath (Join-Path $output 'result.json') -ErrorAction SilentlyContinue; throw 'Automatic MSI recovery acceptance failed. Isolated artifacts are retained for diagnosis.' } } finally { $test.Dispose() }
Get-Content -LiteralPath (Join-Path $output 'result.json')
