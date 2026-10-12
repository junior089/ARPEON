param(
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('setup','maintenance')][string]$State = 'setup'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class ArpeonInstallerCapture {
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window, out Rect bounds);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr window, IntPtr dc, uint flags);
}
'@
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$process = Start-Process msiexec.exe -ArgumentList @('/i', ('"{0}"' -f [IO.Path]::GetFullPath($Installer)), '/norestart', '/l*v', ('"{0}"' -f (Join-Path $output "$State.log"))) -WindowStyle Hidden -PassThru
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    $window = $null
    do {
        $process.Refresh()
        $window = [Windows.Automation.AutomationElement]::RootElement.FindFirst([Windows.Automation.TreeScope]::Children,
            [Windows.Automation.AndCondition]::new(
                [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::ProcessIdProperty, $process.Id),
                [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::NameProperty, 'ARPEON')))
        if ($window) {
            $readyName = if ($State -eq 'setup') { 'Instalar' } else { 'Reparar arquivos' }
            $ready = $window.FindFirst([Windows.Automation.TreeScope]::Descendants,
                [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::NameProperty, $readyName))
            if ($ready) { break }
            $window = $null
        }
        if ($process.MainWindowHandle -ne [IntPtr]::Zero) {
            $window = [Windows.Automation.AutomationElement]::FromHandle($process.MainWindowHandle)
            if ($window.Current.Name -eq 'ARPEON') {
                $readyName = if ($State -eq 'setup') { 'Instalar' } else { 'Reparar arquivos' }
                $ready = $window.FindFirst([Windows.Automation.TreeScope]::Descendants,
                    [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::NameProperty, $readyName))
                if ($ready) { break }
            }
            $window = $null
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $window) { throw 'The installer did not expose its ARPEON window to UI Automation.' }
    $controls = $window.FindAll([Windows.Automation.TreeScope]::Descendants, [Windows.Automation.Condition]::TrueCondition)
    $names = @($controls | ForEach-Object { $_.Current.Name } | Where-Object { $_ })
    $names | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output "$State-controls.json") -Encoding utf8
    $expected = if ($State -eq 'setup') { @('Pasta do aplicativo', 'Instalar', 'Cancelar') } else { @('Abrir ARPEON', 'Reparar arquivos', 'Desinstalar…', 'Fechar') }
    foreach ($name in $expected) { if ($name -notin $names) { throw "Missing accessible installer control: $name" } }
    $bounds = [ArpeonInstallerCapture+Rect]::new()
    $handle = [IntPtr]$window.Current.NativeWindowHandle
    if (-not [ArpeonInstallerCapture]::GetWindowRect($handle, [ref]$bounds)) { throw 'Cannot measure installer window.' }
    $bitmap = [Drawing.Bitmap]::new($bounds.Right - $bounds.Left, $bounds.Bottom - $bounds.Top)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $dc = $graphics.GetHdc()
    try {
        if (-not [ArpeonInstallerCapture]::PrintWindow($handle, $dc, 2)) { throw 'Cannot capture the installer window.' }
    } finally { $graphics.ReleaseHdc($dc) }
    try { $bitmap.Save((Join-Path $output "$State.png"), [Drawing.Imaging.ImageFormat]::Png) }
    finally { $graphics.Dispose(); $bitmap.Dispose() }
    $names | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output "$State-controls.json") -Encoding utf8
    $closeName = if ($State -eq 'setup') { 'Cancelar' } else { 'Fechar' }
    $close = $window.FindFirst([Windows.Automation.TreeScope]::Descendants, [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::NameProperty, $closeName))
    ([Windows.Automation.InvokePattern]$close.GetCurrentPattern([Windows.Automation.InvokePattern]::Pattern)).Invoke()
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        if ($process.HasExited) { break }
        $windows = [Windows.Automation.AutomationElement]::RootElement.FindAll([Windows.Automation.TreeScope]::Children,
            [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::ProcessIdProperty, $process.Id))
        foreach ($candidate in $windows) {
            $finish = $candidate.FindFirst([Windows.Automation.TreeScope]::Descendants,
                [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::NameProperty, 'Concluir'))
            if ($finish) { ([Windows.Automation.InvokePattern]$finish.GetCurrentPattern([Windows.Automation.InvokePattern]::Pattern)).Invoke() }
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $process.WaitForExit(15000)) { throw 'Cancel/close did not terminate the installer.' }
    Write-Host "Installer $State captured and accessible controls verified. No installation was requested."
} finally {
    if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit(5000) | Out-Null }
    $process.Dispose()
}
