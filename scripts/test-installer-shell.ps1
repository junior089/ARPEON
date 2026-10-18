param(
    [Parameter(Mandatory)][string]$Setup,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$Lifecycle
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath($OutputDirectory)
$allowed = [IO.Path]::GetFullPath((Join-Path $repoRoot 'apps/desktop/build')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $output)) { throw 'Use a new test output directory inside apps/desktop/build.' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$assembly = [Reflection.Assembly]::LoadFile([IO.Path]::GetFullPath($Setup))
$resourceNames = @('bundle.json','inventory.json','payload.msi','Installer.xaml')
if ($assembly.GetManifestResourceNames() -contains 'maintenance.exe') { $resourceNames += 'maintenance.exe' }
foreach ($resource in $resourceNames) {
    $stream = $assembly.GetManifestResourceStream($resource)
    $file = [IO.File]::Create((Join-Path $output $resource))
    try { $stream.CopyTo($file) } finally { $file.Dispose(); $stream.Dispose() }
}
if ($Lifecycle -and ((Get-Content (Join-Path $output 'bundle.json') -Raw | ConvertFrom-Json).RegistryRoot -notlike '*InstallerTest*')) { throw 'Lifecycle requires an isolated test setup.' }
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$references = @('System.dll','System.Core.dll','System.Web.Extensions.dll','System.Windows.Forms.dll') | ForEach-Object { "/reference:$(Join-Path $framework $_)" }
$references += @('WindowsBase.dll','PresentationCore.dll','PresentationFramework.dll','System.Xaml.dll','UIAutomationTypes.dll','UIAutomationProvider.dll') | ForEach-Object {
    $path = Join-Path $framework "WPF/$_"
    if (-not (Test-Path $path)) { $path = Join-Path $framework $_ }
    "/reference:$path"
}
$resources = $resourceNames | ForEach-Object { "/resource:$(Join-Path $output $_),$_" }
$source = Join-Path $repoRoot 'packaging/windows/bootstrapper'
$test = Join-Path $output 'InstallerTests.exe'
& (Join-Path $framework 'csc.exe') /nologo /target:exe /platform:x64 /main:Arpeon.Setup.InstallerTests @references @resources "/out:$test" "/win32manifest:$(Join-Path $source 'app.manifest')" (Join-Path $source 'InstallerEngine.cs') (Join-Path $source 'InstallerWindow.cs') (Join-Path $source 'InstallerTests.cs')
if ($LASTEXITCODE -ne 0) { throw 'Installer test compilation failed.' }
$mode = if ($Lifecycle) { 'lifecycle' } else { 'visual' }
& $test $output $mode
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $output 'failure.txt') -ErrorAction SilentlyContinue | Write-Host; throw 'Installer shell verification failed.' }
if (-not (Test-Path -LiteralPath (Join-Path $output 'result.txt'))) { throw 'The test process ended before completing its assertions. No pass was recorded.' }
Get-Content -LiteralPath (Join-Path $output 'result.txt') | Write-Host
