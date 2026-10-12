param([Parameter(Mandatory)][string]$Installer, [Parameter(Mandatory)][string]$Output)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputPath = [IO.Path]::GetFullPath($Output)
$allowed = [IO.Path]::GetFullPath((Join-Path $repoRoot 'apps/desktop/build')) + [IO.Path]::DirectorySeparatorChar
if (-not $outputPath.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $outputPath)) { throw 'Fault fixtures must use a new file inside apps/desktop/build.' }
Copy-Item -LiteralPath $Installer -Destination $outputPath
$windowsInstaller = New-Object -ComObject WindowsInstaller.Installer
$database = $windowsInstaller.OpenDatabase($outputPath, 1)
try {
    $query = 'SELECT {0}Value{0} FROM {0}Property{0} WHERE {0}Property{0}=''ProductName''' -f [char]96
    $view = $database.OpenView($query)
    try {
        $view.Execute() | Out-Null
        $record = $view.Fetch()
        $name = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
        if ($name -notmatch '^ARPEON Lifecycle Test(?: [a-z0-9-]+)?$') { throw 'Fault injection requires an isolated lifecycle test MSI.' }
    } finally { $view.Close() | Out-Null }
    foreach ($sql in @(
        "INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Target``) VALUES ('ArpeonTestFailure', 19, 'Injected failure for rollback acceptance; never distribute this artifact.')",
        "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Condition``, ``Sequence``) VALUES ('ArpeonTestFailure', 'NOT Installed', 4001)"
    )) {
        $view = $database.OpenView($sql)
        try { $view.Execute() | Out-Null } finally { $view.Close() | Out-Null }
    }
    $database.Commit() | Out-Null
    # Altered fixtures must not share the production package's cache identity.
    $summary = $database.GetType().InvokeMember('SummaryInformation', 'GetProperty', $null, $database, @(1))
    $summary.GetType().InvokeMember('Property', 'SetProperty', $null, $summary, @(9, [Guid]::NewGuid().ToString('B').ToUpperInvariant())) | Out-Null
    $summary.Persist() | Out-Null
} finally { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null }
Write-Host "Fault fixture created: $outputPath. Test use only; any publisher signature is invalid."
