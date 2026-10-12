param(
    [Parameter(Mandatory)][string]$Package,
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$TestIdentity,
    [ValidatePattern('^[a-z0-9-]{0,24}$')][string]$TestRunId = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root = [IO.Path]::GetFullPath($Package)
$output = [IO.Path]::GetFullPath($OutputDirectory)
$wix = Join-Path $repoRoot '.tools/production/wix314'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'MSI requires major.minor.patch.' }
$numeric = [Version]::Parse($Version)
if ($numeric.Major -gt 255 -or $numeric.Minor -gt 255 -or $numeric.Build -gt 65535) { throw 'Version exceeds MSI limits.' }
& (Join-Path $PSScriptRoot 'verify-package.ps1') -Package $root
$stage = Join-Path $output "installer-$Version"
if ((Test-Path -LiteralPath $stage) -or (Test-Path -LiteralPath (Join-Path $output "ARPEON-$Version.msi"))) {
    throw 'Installer output already exists. MSI sources are immutable; use a new output directory and test run identity.'
}
New-Item -ItemType Directory -Path $stage -Force | Out-Null

function Escape-Xml([string]$Value) { return [Security.SecurityElement]::Escape($Value) }
function Get-Id([string]$Value) {
    return 'i' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Value))).Substring(0, 30)
}
function Get-StableGuid([string]$Value) {
    $hash = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes("org.arpeon.windows/$Value"))
    return [Guid]::new([byte[]]$hash[0..15]).ToString('B').ToUpperInvariant()
}
$productCode = Get-StableGuid "product/$Version"
$upgradeCode = '8F73BCEE-0F37-4776-A07E-0BE97DD65F23'
$registryRoot = 'Software\ARPEON'
$productName = 'ARPEON'
$testDefine = @()
if ($TestIdentity) {
    $productCode = Get-StableGuid "test-product/$TestRunId/$Version"
    $upgradeCode = '62435DA5-EBA3-4AC7-A87A-A1ED5CCB189A'
    $registryRoot = 'Software\ARPEON\InstallerTest'
    $productName = 'ARPEON Lifecycle Test'
    if ($TestRunId) {
        $upgradeCode = Get-StableGuid "test-upgrade/$TestRunId"
        $registryRoot += "\$TestRunId"
        $productName += " $TestRunId"
    }
    $testDefine = @('-dTestIdentity=yes')
}
elseif ($TestRunId) { throw 'TestRunId requires an isolated test identity.' }
$inventory = Get-Content -LiteralPath (Join-Path $root 'FILES-SHA256.json') -Raw | ConvertFrom-Json
$directories = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$files = @($inventory.path) + 'FILES-SHA256.json'
$components = [Text.StringBuilder]::new()
foreach ($relative in ($files | Sort-Object)) {
    $source = [IO.Path]::GetFullPath((Join-Path $root $relative))
    if (-not $source.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Package path escapes root.' }
    $parent = [IO.Path]::GetDirectoryName($relative).Replace('\', '/')
    $directory = $parent
    while ($directory) {
        $null = $directories.Add($directory)
        $directory = [IO.Path]::GetDirectoryName($directory).Replace('\', '/')
    }
    $dirId = if ($parent) { Get-Id "dir/$parent" } else { 'INSTALLDIR' }
    $id = Get-Id "file/$relative"
    $guid = Get-StableGuid "$registryRoot/file/$relative"
    $null = $components.AppendLine("<Component Id='$id' Guid='$guid' Directory='$dirId' Win64='yes'>")
    $null = $components.AppendLine("<File Id='f$id' Source='$(Escape-Xml $source)' Name='$(Escape-Xml ([IO.Path]::GetFileName($relative)))' />")
    $null = $components.AppendLine("<RegistryValue Root='HKCU' Key='$registryRoot\Installer\Components' Name='$id' Type='integer' Value='1' KeyPath='yes' />")
    $null = $components.AppendLine('</Component>')
}
$tree = [Text.StringBuilder]::new()
foreach ($directory in ($directories | Sort-Object)) {
    $parent = [IO.Path]::GetDirectoryName($directory).Replace('\', '/')
    $parentId = if ($parent) { Get-Id "dir/$parent" } else { 'INSTALLDIR' }
    $null = $tree.AppendLine("<DirectoryRef Id='$parentId'><Directory Id='$(Get-Id "dir/$directory")' Name='$(Escape-Xml ([IO.Path]::GetFileName($directory)))' /></DirectoryRef>")
    $id = Get-Id "cleanup/$directory"
    $guid = Get-StableGuid "$registryRoot/cleanup/$directory"
    $null = $components.AppendLine("<Component Id='$id' Guid='$guid' Directory='$(Get-Id "dir/$directory")' Win64='yes'><RegistryValue Root='HKCU' Key='$registryRoot\Installer\Components' Name='$id' Type='integer' Value='1' KeyPath='yes' /><RemoveFolder Id='r$id' On='uninstall' /></Component>")
}
# Only MSI-owned files are removed. No RemoveFolderEx or recursive install-directory cleanup.
$bundle = "<?xml version='1.0' encoding='utf-8'?><Wix xmlns='http://schemas.microsoft.com/wix/2006/wi'><Fragment>$tree<ComponentGroup Id='ApplicationFiles'>$components</ComponentGroup></Fragment></Wix>"
[IO.File]::WriteAllText((Join-Path $stage 'files.wxs'), $bundle, [Text.UTF8Encoding]::new($false))

Add-Type -AssemblyName System.Drawing
$bitmap = [Drawing.Bitmap]::new(740, 100)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$logo = [Drawing.Image]::FromFile((Join-Path $repoRoot 'frontend/public/brand/arpeon-256.png'))
$font = [Drawing.Font]::new('Segoe UI', 23, [Drawing.FontStyle]::Bold)
$gold = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#E6C989'))
$text = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#F1F1F3'))
try {
    $graphics.Clear([Drawing.ColorTranslator]::FromHtml('#141416'))
    $graphics.DrawImage($logo, 30, 14, 64, 64)
    $graphics.DrawString('ARPEON', $font, $text, 110, 22)
    $graphics.FillRectangle($gold, 0, 96, 740, 4)
    $bitmap.Save((Join-Path $stage 'banner.bmp'), [Drawing.Imaging.ImageFormat]::Bmp)
} finally { $graphics.Dispose(); $bitmap.Dispose(); $logo.Dispose(); $font.Dispose(); $gold.Dispose(); $text.Dispose() }

$main = Join-Path $repoRoot 'packaging/windows/installer.wxs'
$ui = Join-Path $repoRoot 'packaging/windows/ui.wxs'
$folder = if ($TestIdentity) { $productName } else { 'ARPEON Desktop' }
& (Join-Path $wix 'candle.exe') -nologo -arch x64 @testDefine "-dInstallFolder=$folder" "-dInstallationGuid=$(Get-StableGuid "$registryRoot/identity")" "-dDesktopGuid=$(Get-StableGuid "$registryRoot/desktop")" "-dMenuGuid=$(Get-StableGuid "$registryRoot/menu")" "-dProductName=$productName" "-dRegistryRoot=$registryRoot" "-dUpgradeCode=$upgradeCode" "-dAppVersion=$Version" "-dProductCode=$productCode" "-dPackageRoot=$root" "-dBanner=$(Join-Path $stage 'banner.bmp')" -out "$stage\" $main $ui (Join-Path $stage 'files.wxs')
if ($LASTEXITCODE -ne 0) { throw 'WiX compilation failed.' }
$msi = Join-Path $output "ARPEON-$Version.msi"
# ICE91 warns about supporting per-machine installs, which this product deliberately forbids.
& (Join-Path $wix 'light.exe') -nologo -ext WixUIExtension -cultures:pt-br -sice:ICE91 -cc (Join-Path $output 'cabinet-cache') -reusecab -out $msi (Join-Path $stage 'installer.wixobj') (Join-Path $stage 'ui.wixobj') (Join-Path $stage 'files.wixobj')
if ($LASTEXITCODE -ne 0) { throw 'WiX linking or MSI validation failed.' }
Write-Host "Windows installer: $msi"
