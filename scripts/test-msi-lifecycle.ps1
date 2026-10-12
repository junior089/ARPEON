param(
    [Parameter(Mandatory)][string]$BaseInstaller,
    [Parameter(Mandatory)][string]$UpgradeInstaller,
    [Parameter(Mandatory)][string]$WorkingDirectory,
    [string]$FailingUpgradeInstaller = '',
    [switch]$TestIdentity,
    [ValidatePattern('^[a-z0-9-]{0,24}$')][string]$TestRunId = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$work = [IO.Path]::GetFullPath($WorkingDirectory)
$productName = if ($TestIdentity) { 'ARPEON Lifecycle Test' } else { 'ARPEON' }
$installationKey = if ($TestIdentity) { 'HKCU:\Software\ARPEON\InstallerTest\Installation' } else { 'HKCU:\Software\ARPEON\Installation' }
if ($TestRunId) {
    if (-not $TestIdentity) { throw 'TestRunId requires isolated test identity.' }
    $productName += " $TestRunId"
    $installationKey = "HKCU:\Software\ARPEON\InstallerTest\$TestRunId\Installation"
}
$allowed = [IO.Path]::GetFullPath((Join-Path $repoRoot 'apps/desktop/build')) + [IO.Path]::DirectorySeparatorChar
if (-not $work.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $work)) { throw 'Use a new isolated directory inside apps/desktop/build.' }
$uninstallRoots = @(
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKCU:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
)
$existing = @($uninstallRoots | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object { Get-ChildItem -LiteralPath $_ } | ForEach-Object { Get-ItemProperty $_.PSPath } | Where-Object { $_.PSObject.Properties.Name -contains 'DisplayName' -and $_.DisplayName -eq $productName })
if ($existing.Count) { throw 'An ARPEON installation already exists. Run lifecycle acceptance in a disposable Windows profile.' }
New-Item -ItemType Directory -Path $work -Force | Out-Null
$target = Join-Path $work 'instalação com espaços — ARPEON'
$profile = Join-Path $work 'profile'

function Get-ProductCode([string]$Msi) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase([IO.Path]::GetFullPath($Msi), 0)
    $view = $database.OpenView('SELECT `Value` FROM `Property` WHERE `Property`=''ProductCode''')
    $view.Execute() | Out-Null
    $record = $view.Fetch()
    $value = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
    $view.Close() | Out-Null
    $query = 'SELECT {0}Value{0} FROM {0}Property{0} WHERE {0}Property{0}=''ProductName''' -f [char]96
    $view = $database.OpenView($query)
    $view.Execute() | Out-Null
    $record = $view.Fetch()
    $name = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
    $view.Close() | Out-Null
    if ($name -ne $productName) { throw 'Installer identity does not match this acceptance run.' }
    if ($value -notmatch '^\{[A-Fa-f0-9-]{36}\}$') { throw 'Invalid MSI product code.' }
    return $value
}
function Invoke-Msi([string]$Action, [string]$Artifact, [string]$Log, [switch]$Install, [int]$ExpectedCode = 0) {
    $arguments = @($Action, "`"$Artifact`"", '/qn', '/norestart', '/l*v', "`"$(Join-Path $work $Log)`"")
    if ($Install) { $arguments += "INSTALLDESKTOPSHORTCUT=0"; $arguments += "INSTALLDIR=`"$target`"" }
    $process = Start-Process msiexec.exe -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne $ExpectedCode) { throw "Windows Installer returned $($process.ExitCode), expected $ExpectedCode; see $Log." }
}
function Snapshot-Data {
    return @(Get-ChildItem -LiteralPath $profile -Recurse -File | Where-Object {
        $_.FullName.StartsWith((Join-Path $profile 'projects') + [IO.Path]::DirectorySeparatorChar) -or $_.Name -in @('preferences.json', 'audio-preferences.json')
    } | Sort-Object FullName | ForEach-Object { @{ path = [IO.Path]::GetRelativePath($profile, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash } })
}
$baseCode = Get-ProductCode $BaseInstaller
$upgradeCode = Get-ProductCode $UpgradeInstaller
if ($baseCode -eq $upgradeCode) { throw 'Lifecycle acceptance requires two distinct versions/product codes.' }
$installedCode = $null
try {
    Invoke-Msi '/i' ([IO.Path]::GetFullPath($BaseInstaller)) 'install.log' -Install
    $installedCode = $baseCode
    if ((Get-ItemProperty -LiteralPath $installationKey).DesktopShortcut -ne '0') { throw 'Installer ignored explicit desktop shortcut preference.' }
    $extra = Join-Path $target 'documento-do-usuário.txt'
    Set-Content -LiteralPath $extra -Value 'User-owned file: MSI must never remove this.' -Encoding utf8
    $extraHash = (Get-FileHash -LiteralPath $extra).Hash
    $launcherHash = (Get-FileHash -LiteralPath (Join-Path $target 'ARPEON.exe')).Hash
    Invoke-Msi '/i' ([IO.Path]::GetFullPath($BaseInstaller)) 'same-version.log'
    if ((Get-FileHash -LiteralPath (Join-Path $target 'ARPEON.exe')).Hash -ne $launcherHash) { throw 'Same-version install changed launcher.' }
    $repairFile = Join-Path $target 'config/updates.properties'
    $repairHash = (Get-FileHash -LiteralPath $repairFile).Hash
    Remove-Item -LiteralPath $repairFile
    Invoke-Msi '/fa' ([IO.Path]::GetFullPath($BaseInstaller)) 'repair.log'
    if ((Get-FileHash -LiteralPath $repairFile).Hash -ne $repairHash) { throw 'MSI repair did not restore a missing file.' }
    & (Join-Path $PSScriptRoot 'test-installed-launcher.ps1') -Package $target -Profile $profile
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $classpath = "$(Join-Path $repoRoot 'apps/desktop/build/classes/java/test');$(Join-Path $target 'app/*')"
    & $java --enable-native-access=ALL-UNNAMED '-Dfile.encoding=UTF-8' -classpath $classpath org.arpeon.desktop.NativeDesktopAcceptance $target $profile (Join-Path $work 'before-upgrade') (Join-Path $repoRoot 'fixtures/musicxml/first-notes.musicxml')
    if ($LASTEXITCODE -ne 0) { throw 'Installed UI acceptance failed.' }
    $before = Snapshot-Data
    if ($before.Count -lt 3) { throw 'No real projects/preferences were created for preservation acceptance.' }
    if ($FailingUpgradeInstaller) {
        Invoke-Msi '/i' ([IO.Path]::GetFullPath($FailingUpgradeInstaller)) 'rollback.log' -ExpectedCode 1603
        $rollbackLog = Get-Content -LiteralPath (Join-Path $work 'rollback.log') -Raw
        if ($rollbackLog -notmatch 'Action start .*ArpeonTestFailure' -or $rollbackLog -notmatch 'Action start .*RemoveExistingProducts' -or
            (Get-ItemProperty -LiteralPath $installationKey).ProductCode -ne $baseCode) { throw 'Failure fixture did not exercise and restore the original registered installation.' }
        $windowsInstaller = New-Object -ComObject WindowsInstaller.Installer
        $productState = $windowsInstaller.GetType().InvokeMember('ProductState', 'GetProperty', $null, $windowsInstaller, @($baseCode))
        if ($rollbackLog.Contains('Error in rollback skipped') -or $productState -ne 5) {
            @{ rollbackFilesRestored = (Test-Path -LiteralPath (Join-Path $target 'ARPEON.exe')); rollbackRegistrationRestored = ($productState -eq 5); cleanMachine = $false; failure = 'Windows Installer registry rollback failed; publication remains blocked.' } |
                ConvertTo-Json | Set-Content -LiteralPath (Join-Path $work 'rollback-failure.json') -Encoding utf8
            throw 'Rollback did not restore Windows Installer registration. File presence alone is not a passed rollback. See rollback-failure.json and rollback.log.'
        }
        if ((Get-FileHash -LiteralPath (Join-Path $target 'ARPEON.exe')).Hash -ne $launcherHash) { throw 'Failed upgrade did not restore the previous executable.' }
        & (Join-Path $PSScriptRoot 'test-installed-launcher.ps1') -Package $target -Profile $profile
    }
    # No INSTALLDIR is supplied: the installer must recover the registered custom path.
    Invoke-Msi '/i' ([IO.Path]::GetFullPath($UpgradeInstaller)) 'upgrade.log'
    $installedCode = $upgradeCode
    & (Join-Path $PSScriptRoot 'test-installed-launcher.ps1') -Package $target -Profile $profile
    foreach ($entry in $before) {
        if ((Get-FileHash -LiteralPath (Join-Path $profile $entry.path)).Hash -ne $entry.sha256) { throw "Upgrade changed persisted data: $($entry.path)" }
    }
    $installed = @($uninstallRoots | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object { Get-ChildItem -LiteralPath $_ } | ForEach-Object { Get-ItemProperty $_.PSPath } | Where-Object { $_.PSObject.Properties.Name -contains 'DisplayName' -and $_.DisplayName -eq $productName })
    if ($installed.Count -ne 1) { throw 'Upgrade left multiple Windows app registrations.' }
    Invoke-Msi '/i' ([IO.Path]::GetFullPath($BaseInstaller)) 'downgrade.log' -ExpectedCode 1603
    if ((Get-ItemProperty -LiteralPath $installationKey).ProductCode -ne $upgradeCode) { throw 'Downgrade changed the installation identity.' }
    if ((Get-ItemProperty -LiteralPath $installationKey).DesktopShortcut -ne '0') { throw 'Upgrade changed shortcut preference.' }
    if ((Get-FileHash -LiteralPath $extra).Hash -ne $extraHash) { throw 'Upgrade changed an unowned file.' }
    Invoke-Msi '/x' $installedCode 'uninstall.log'
    $installedCode = $null
    if (Test-Path -LiteralPath (Join-Path $target 'ARPEON.exe')) { throw 'Uninstall left the application launcher.' }
    foreach ($entry in $before) {
        if ((Get-FileHash -LiteralPath (Join-Path $profile $entry.path)).Hash -ne $entry.sha256) { throw "Uninstall changed persisted data: $($entry.path)" }
    }
    if ((Get-FileHash -LiteralPath $extra).Hash -ne $extraHash) { throw 'Uninstall removed an unowned file.' }
    Invoke-Msi '/i' ([IO.Path]::GetFullPath($UpgradeInstaller)) 'reinstall.log' -Install
    $installedCode = $upgradeCode
    & (Join-Path $PSScriptRoot 'test-installed-launcher.ps1') -Package $target -Profile $profile
    foreach ($entry in $before) {
        if ((Get-FileHash -LiteralPath (Join-Path $profile $entry.path)).Hash -ne $entry.sha256) { throw 'Reinstallation changed user data.' }
    }
    Invoke-Msi '/x' $installedCode 'reinstall-uninstall.log'
    $installedCode = $null
    @{ install = $true; sameVersion = $true; repair = $true; customUnicodeDirectory = $true; upgrade = $true; downgradeBlocked = $true; rollback = [bool]$FailingUpgradeInstaller; uninstall = $true; reinstall = $true; unownedFilePreserved = $true; preservedFiles = $before.Count; cleanMachine = $false } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $work 'result.json') -Encoding utf8
} finally {
    if ($installedCode) { Invoke-Msi '/x' $installedCode 'cleanup-uninstall.log' }
}
