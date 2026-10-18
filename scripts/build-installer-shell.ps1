param(
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][string]$Package,
    [Parameter(Mandatory)][string]$Output
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$msi = [IO.Path]::GetFullPath($Installer)
$root = [IO.Path]::GetFullPath($Package)
$exe = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $exe) { throw 'Setup output exists. Use a new output path to keep artifacts immutable.' }
& (Join-Path $PSScriptRoot 'verify-package.ps1') -Package $root
$source = Join-Path $repoRoot 'packaging/windows/bootstrapper'
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$compiler = Join-Path $framework 'csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw '.NET Framework 4 compiler and WPF are required to build the native setup.' }
$stage = Join-Path ([IO.Path]::GetDirectoryName($exe)) ('shell-' + [IO.Path]::GetFileNameWithoutExtension($exe))
if (Test-Path -LiteralPath $stage) { throw 'Shell staging output exists. Use a new output path.' }
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$installerCom = New-Object -ComObject WindowsInstaller.Installer
$database = $installerCom.OpenDatabase($msi, 0)
function Read-MsiValue([string]$Query) {
    $view = $database.OpenView($Query)
    try {
        $view.Execute() | Out-Null
        $record = $view.Fetch()
        if (-not $record) { throw "Missing MSI metadata for $Query" }
        return $record.StringData(1)
    } finally { $view.Close() | Out-Null }
}
try {
    $version = Read-MsiValue 'SELECT `Value` FROM `Property` WHERE `Property`=''ProductVersion'''
    $productName = Read-MsiValue 'SELECT `Value` FROM `Property` WHERE `Property`=''ProductName'''
    $productCode = Read-MsiValue 'SELECT `Value` FROM `Property` WHERE `Property`=''ProductCode'''
    $upgradeCode = Read-MsiValue 'SELECT `Value` FROM `Property` WHERE `Property`=''UpgradeCode'''
    $folder = Read-MsiValue 'SELECT `DefaultDir` FROM `Directory` WHERE `Directory`=''INSTALLDIR'''
    $registry = Read-MsiValue 'SELECT `Key` FROM `Registry` WHERE `Name`=''InstallLocation'''
} finally {
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installerCom) | Out-Null
}
$inventoryFile = Join-Path $root 'FILES-SHA256.json'
$inventory = @(Get-Content -LiteralPath $inventoryFile -Raw | ConvertFrom-Json)
$buildInfo = Get-Content -LiteralPath (Join-Path $root 'BUILD-INFO.json') -Raw | ConvertFrom-Json
if ($buildInfo.version -ne $version) { throw 'The MSI version and application image version must match.' }
@{
    Version = $version; ProductCode = $productCode; UpgradeCode = $upgradeCode; ProductName = $productName
    InstallFolder = ($folder -split '\|')[-1]; RegistryRoot = $registry -replace '\\Installation$', ''
    MsiHash = (Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash.ToLowerInvariant()
    MsiSize = (Get-Item -LiteralPath $msi).Length
    HasMaintenance = $true
    InstalledSize = [long](($inventory | Measure-Object -Property size -Sum).Sum + (Get-Item -LiteralPath $inventoryFile).Length)
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'bundle.json') -Encoding utf8
@"
using System.Reflection;
[assembly: AssemblyTitle("ARPEON Installer")]
[assembly: AssemblyProduct("ARPEON")]
[assembly: AssemblyCompany("ARPEON")]
[assembly: AssemblyVersion("$version.0")]
[assembly: AssemblyFileVersion("$version.0")]
"@ | Set-Content -LiteralPath (Join-Path $stage 'Version.cs') -Encoding utf8
$references = @('System.dll','System.Core.dll','System.Web.Extensions.dll','System.Windows.Forms.dll') | ForEach-Object { "/reference:$(Join-Path $framework $_)" }
$references += @('WindowsBase.dll','PresentationCore.dll','PresentationFramework.dll','System.Xaml.dll','UIAutomationTypes.dll','UIAutomationProvider.dll') | ForEach-Object {
    $location = Join-Path $framework "WPF/$_"
    if (-not (Test-Path -LiteralPath $location)) { $location = Join-Path $framework $_ }
    "/reference:$location"
}
$maintenance = Join-Path $stage 'ARPEON-Maintenance.exe'
& $compiler /nologo /target:winexe /platform:x64 /optimize+ /utf8output /warn:4 @references `
    "/out:$maintenance" "/win32manifest:$(Join-Path $source 'app.manifest')" "/win32icon:$(Join-Path $repoRoot 'packaging/arpeon.ico')" `
    "/resource:$inventoryFile,inventory.json" "/resource:$(Join-Path $stage 'bundle.json'),bundle.json" "/resource:$(Join-Path $source 'Installer.xaml'),Installer.xaml" `
    (Join-Path $source 'InstallerEngine.cs') (Join-Path $source 'InstallerWindow.cs') (Join-Path $stage 'Version.cs')
if ($LASTEXITCODE -ne 0) { throw 'Native maintenance compilation failed.' }
if ($env:ARPEON_SIGN_CERT_THUMBPRINT -and $env:ARPEON_TIMESTAMP_URL) {
    $signer = Get-Command signtool.exe -ErrorAction Stop
    & $signer.Source sign /sha1 $env:ARPEON_SIGN_CERT_THUMBPRINT /s My /fd SHA256 /tr $env:ARPEON_TIMESTAMP_URL /td SHA256 $maintenance
    if ($LASTEXITCODE -ne 0) { throw 'Maintenance signing failed.' }
    & $signer.Source verify /pa /v $maintenance
    if ($LASTEXITCODE -ne 0) { throw 'Maintenance signature verification failed.' }
}
& $compiler /nologo /target:winexe /platform:x64 /optimize+ /utf8output /warn:4 @references `
    "/out:$exe" "/win32manifest:$(Join-Path $source 'app.manifest')" "/win32icon:$(Join-Path $repoRoot 'packaging/arpeon.ico')" `
    "/resource:$msi,payload.msi" "/resource:$inventoryFile,inventory.json" `
    "/resource:$maintenance,maintenance.exe" `
    "/resource:$(Join-Path $stage 'bundle.json'),bundle.json" "/resource:$(Join-Path $source 'Installer.xaml'),Installer.xaml" `
    (Join-Path $source 'InstallerEngine.cs') (Join-Path $source 'InstallerWindow.cs') (Join-Path $stage 'Version.cs')
if ($LASTEXITCODE -ne 0) { throw 'Native installer shell compilation failed.' }
$checksum = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$exe.sha256" -Value "$checksum  $([IO.Path]::GetFileName($exe))" -Encoding ascii
$sourceRoot = Join-Path $stage 'source'
$sourceCode = Join-Path $sourceRoot 'packaging/windows/bootstrapper'
$sourceScripts = Join-Path $sourceRoot 'scripts'
New-Item -ItemType Directory -Path $sourceCode,$sourceScripts -Force | Out-Null
Get-ChildItem -LiteralPath $source -File | Copy-Item -Destination $sourceCode
Get-ChildItem -LiteralPath (Join-Path $repoRoot 'packaging/windows') -File | Copy-Item -Destination (Split-Path $sourceCode)
Copy-Item -LiteralPath (Join-Path $repoRoot 'packaging/arpeon.ico') -Destination (Join-Path $sourceRoot 'packaging')
$sourceBrand = Join-Path $sourceRoot 'frontend/public/brand'
New-Item -ItemType Directory -Path $sourceBrand -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repoRoot 'frontend/public/brand/arpeon-256.png') -Destination $sourceBrand
foreach ($script in @('build-installer-shell.ps1','build-windows-installer.ps1','verify-package.ps1','test-installer-shell.ps1','test-installer-transactions.ps1','test-installer-recovery.ps1','new-failing-test-msi.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $script) -Destination $sourceScripts
}
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination $sourceRoot
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs/development/installer-design.md') -Destination $sourceRoot
$sourceZip = Join-Path ([IO.Path]::GetDirectoryName($exe)) 'ARPEON-Installer-Source.zip'
Compress-Archive -Path (Join-Path $sourceRoot '*') -DestinationPath $sourceZip
Write-Host "Native ARPEON setup: $exe"
