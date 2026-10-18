param(
    [Parameter(Mandatory)][string]$ApplicationImage,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidatePattern('^[a-z0-9-]{1,24}$')][string]$TestRunId = 'transactions',
    [switch]$SkipFaultInjection
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath($OutputDirectory)
$allowed = [IO.Path]::GetFullPath((Join-Path $repoRoot 'apps/desktop/build')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $output)) { throw 'Use a new fixture directory inside apps/desktop/build.' }
$registry = "HKCU:\Software\ARPEON\InstallerTest\$TestRunId\Installation"
if (Test-Path $registry) { throw 'Use a fresh transaction test identity.' }
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
Write-Fixture 'Version 0.1.1 transaction fixture. Never distribute or launch.'
& (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -Package $fixture -Version 0.1.1 -OutputDirectory (Join-Path $output 'base') -TestIdentity -TestRunId $TestRunId -SkipBootstrapper
Write-Fixture 'Version 0.1.2 transaction fixture. Never distribute or launch.'
& (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -Package $fixture -Version 0.1.2 -OutputDirectory (Join-Path $output 'upgrade') -TestIdentity -TestRunId $TestRunId -SkipBootstrapper
$base = Join-Path $output 'base/ARPEON-0.1.1.msi'
$upgrade = Join-Path $output 'upgrade/ARPEON-0.1.2.msi'
$failure = Join-Path $output 'failing-upgrade.msi'
& (Join-Path $PSScriptRoot 'new-failing-test-msi.ps1') -Installer $upgrade -Output $failure
function Get-Code([string]$Path) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase($Path, 0)
    try {
        $view = $database.OpenView('SELECT `Value` FROM `Property` WHERE `Property`=''ProductCode''')
        try { $view.Execute() | Out-Null; return $view.Fetch().StringData(1) } finally { $view.Close() | Out-Null }
    } finally { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null; [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null }
}
$baseCode = Get-Code $base
$upgradeCode = Get-Code $upgrade
$target = Join-Path $output 'instalação — transações'
function Invoke-Transaction([string]$Package, [string]$Name, [string]$Action = '/i', [int]$Expected = 0) {
    $arguments = @($Action, ('"{0}"' -f $Package), '/qn', '/norestart', '/l*v', ('"{0}"' -f (Join-Path $output "$Name.log")))
    if ($Action -eq '/i') { $arguments += @('INSTALLDESKTOPSHORTCUT=0','INSTALLMENUSHORTCUT=0',('INSTALLDIR="{0}"' -f $target)) }
    $process = Start-Process msiexec.exe -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
    try { if ($process.ExitCode -ne $Expected) { throw "$Name returned $($process.ExitCode), expected $Expected. See its log." } }
    finally { $process.Dispose() }
}
Add-Type @'
using System.Runtime.InteropServices;
public static class ArpeonTransactionState {
    [DllImport("msi.dll", CharSet=CharSet.Unicode)] public static extern int MsiQueryProductState(string code);
}
'@
$result = [ordered]@{ fixtureOnly = $true; fullRuntimePayload = $false; faultInjectionSkipped = [bool]$SkipFaultInjection; nativeUpgrade = $false; downgradeBlocked = $false; rollbackFiles = $false; rollbackRegistration = $false; recovery = $false; unownedFilePreserved = $false; cleanup = $false }
try {
    Invoke-Transaction $base 'install-base'
    $before = (Get-FileHash -LiteralPath (Join-Path $target 'payload.txt')).Hash
    $sentinel = Join-Path $target 'user-owned.txt'; [IO.File]::WriteAllText($sentinel, 'preserve this file')
    if (-not $SkipFaultInjection) {
    Invoke-Transaction $failure 'injected-failure' -Expected 1603
    $result.rollbackFiles = (Test-Path -LiteralPath (Join-Path $target 'payload.txt')) -and (Get-FileHash -LiteralPath (Join-Path $target 'payload.txt')).Hash -eq $before
    $result.rollbackRegistration = [ArpeonTransactionState]::MsiQueryProductState($baseCode) -eq 5
    if (-not $result.rollbackRegistration) {
        # Recover only through the same known MSI; never edit Installer registry internals.
        Invoke-Transaction $base 'recover-base'
        $result.recovery = [ArpeonTransactionState]::MsiQueryProductState($baseCode) -eq 5
    } else { $result.recovery = $true }
    }
    Invoke-Transaction $upgrade 'upgrade'
    $result.nativeUpgrade = (Get-ItemProperty -LiteralPath $registry).Version -eq '0.1.2' -and [ArpeonTransactionState]::MsiQueryProductState($upgradeCode) -eq 5 -and [ArpeonTransactionState]::MsiQueryProductState($baseCode) -ne 5
    Invoke-Transaction $base 'downgrade-blocked' -Expected 1603
    $result.downgradeBlocked = (Get-ItemProperty -LiteralPath $registry).Version -eq '0.1.2'
    Invoke-Transaction $upgradeCode 'uninstall' -Action '/x'
    $result.unownedFilePreserved = (Test-Path -LiteralPath $sentinel) -and [IO.File]::ReadAllText($sentinel) -eq 'preserve this file'
} finally {
    foreach ($code in @($upgradeCode, $baseCode)) {
        if ([ArpeonTransactionState]::MsiQueryProductState($code) -eq 5) { Invoke-Transaction $code ('cleanup-' + $code.Trim('{}')) -Action '/x' }
    }
    $result.cleanup = [ArpeonTransactionState]::MsiQueryProductState($baseCode) -ne 5 -and [ArpeonTransactionState]::MsiQueryProductState($upgradeCode) -ne 5
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
}
$result | ConvertTo-Json | Write-Host
if (-not $result.nativeUpgrade -or -not $result.downgradeBlocked -or -not $result.unownedFilePreserved -or -not $result.cleanup) { throw 'Native transaction acceptance failed.' }
if (-not $SkipFaultInjection -and -not $result.rollbackRegistration) { Write-Warning 'Rollback registration did not restore automatically. The result records recovery separately; the production recovery gate remains open.' }
