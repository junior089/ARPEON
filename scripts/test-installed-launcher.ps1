param([Parameter(Mandatory)][string]$Package, [Parameter(Mandatory)][string]$Profile)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath($Package)
$profileRoot = [IO.Path]::GetFullPath($Profile)
if ($profileRoot.Contains('"')) { throw 'Invalid profile path.' }
New-Item -ItemType Directory -Path $profileRoot -Force | Out-Null
$start = [Diagnostics.ProcessStartInfo]::new((Join-Path $root 'ARPEON.exe'))
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.Environment['JAVA_HOME'] = ''
$start.Environment['PATH'] = "$env:SystemRoot\System32;$env:SystemRoot"
$start.Environment['JAVA_TOOL_OPTIONS'] = "`"-Darpeon.dataDir=$profileRoot`""
$start.Environment.Remove('ARPEON_AUDIVERIS_EXECUTABLE') | Out-Null
$start.Environment.Remove('_JAVA_OPTIONS') | Out-Null
$start.Environment.Remove('JDK_JAVA_OPTIONS') | Out-Null
$launcher = [Diagnostics.Process]::Start($start)
$owned = @()
$window = $null
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(40)
    do {
        $owned = @(Get-CimInstance Win32_Process | Where-Object {
            $_.ParentProcessId -eq $launcher.Id -and $_.ExecutablePath -eq (Join-Path $root 'ARPEON.exe')
        } | ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
        $candidates = @($launcher) + $owned
        $window = $candidates | Where-Object { $_.Refresh(); $_.MainWindowTitle -eq 'ARPEON' -and $_.MainWindowHandle -ne 0 } | Select-Object -First 1
        if ($window) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $window) { throw 'Installed launcher did not create its native window. Inspect the isolated profile logs.' }
    if (-not $window.CloseMainWindow()) { throw 'Native close request failed.' }
    if (-not $launcher.WaitForExit(20000)) { throw 'Native close did not terminate the launcher.' }
    Write-Host 'Installed native window opened and closed with Java/Node absent from PATH.'
} finally {
    foreach ($process in (@($launcher) + $owned)) {
        if (-not $process.HasExited) { $process.Kill($true); $process.WaitForExit(10000) | Out-Null }
        $process.Dispose()
    }
}
