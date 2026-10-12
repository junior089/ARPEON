param([Parameter(Mandatory)][string]$Package, [string]$Results = '')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$packageRoot = [IO.Path]::GetFullPath($Package)
$output = if ($Results) { [IO.Path]::GetFullPath($Results) } else { Join-Path $repoRoot "apps/desktop/build/native-acceptance/$([Guid]::NewGuid().ToString('N'))" }
if (Test-Path -LiteralPath $output) { throw 'Acceptance output must be a new isolated directory.' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$classpath = "$(Join-Path $repoRoot 'apps/desktop/build/classes/java/test');$(Join-Path $packageRoot 'app/*')"
& (Join-Path $PSScriptRoot 'verify-package.ps1') -Package $packageRoot
& (Join-Path $PSScriptRoot 'test-installed-launcher.ps1') -Package $packageRoot -Profile (Join-Path $output 'launcher-profile')
& $java --enable-native-access=ALL-UNNAMED '-Dfile.encoding=UTF-8' -classpath $classpath org.arpeon.desktop.NativeDesktopAcceptance $packageRoot (Join-Path $output 'ui-profile') (Join-Path $output 'ui-results') (Join-Path $repoRoot 'fixtures/musicxml/first-notes.musicxml')
if ($LASTEXITCODE -ne 0) { throw 'Native UI acceptance failed.' }
& $java --enable-native-access=ALL-UNNAMED '-Dfile.encoding=UTF-8' -classpath $classpath org.arpeon.desktop.BundledOmrAcceptance (Join-Path $packageRoot 'tools/audiveris-5.11.0') (Join-Path $output 'omr-profile') (Join-Path $repoRoot 'fixtures/omr') (Join-Path $output 'omr-results')
if ($LASTEXITCODE -ne 0) { throw 'Bundled OMR acceptance failed.' }
Write-Host "Native package acceptance results: $output"
