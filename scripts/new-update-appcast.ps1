param(
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][Uri]$DownloadUrl,
    [Parameter(Mandatory)][Uri]$ReleaseNotesUrl,
    [Parameter(Mandatory)][string]$PrivateKeyFile,
    [Parameter(Mandatory)][string]$Output,
    [ValidateSet('stable','beta','nightly','testing')][string]$Channel = 'testing'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Use major.minor.patch; MSI ignores a fourth component.' }
$msiVersion = [Version]::Parse($Version)
if ($msiVersion.Major -gt 255 -or $msiVersion.Minor -gt 255 -or $msiVersion.Build -gt 65535) { throw 'MSI version fields exceed their supported range.' }
foreach ($url in @($DownloadUrl, $ReleaseNotesUrl)) {
    if (-not $url.IsAbsoluteUri -or $url.Scheme -ne 'https' -or $url.UserInfo -or $url.Fragment) { throw 'Release URLs must use HTTPS without credentials or fragments.' }
}
if ($Channel -eq 'stable') { & (Join-Path $PSScriptRoot 'assert-release-ready.ps1') }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tool = Join-Path $repoRoot 'apps/desktop/build/native-resources/build-tools/winsparkle-tool.exe'
if (-not (Test-Path -LiteralPath $tool)) { throw 'Run scripts/prepare-windows-runtime.ps1 first.' }
$artifact = Get-Item -LiteralPath $Installer
$payloadFile = Join-Path ([IO.Path]::GetTempPath()) ('Arpeon-Appcast-' + [Guid]::NewGuid().ToString('N') + '.msi')
$setup = $null; $bundleStream = $null; $payloadStream = $null; $database = $null; $windowsInstaller = $null
try {
    if ($artifact.Extension -ne '.exe') { throw 'Windows updates require the complete ARPEON Setup executable, which performs automatic recovery.' }
    $setup = [Reflection.Assembly]::LoadFile($artifact.FullName)
    $bundleStream = $setup.GetManifestResourceStream('bundle.json')
    $payloadStream = $setup.GetManifestResourceStream('payload.msi')
    if (-not $bundleStream -or -not $payloadStream) { throw 'Update executable is not a complete ARPEON Setup package.' }
    $reader = [IO.StreamReader]::new($bundleStream, [Text.Encoding]::UTF8)
    try { $bundle = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose(); $bundleStream = $null }
    $outputStream = [IO.File]::Create($payloadFile)
    try { $payloadStream.CopyTo($outputStream) } finally { $outputStream.Dispose(); $payloadStream.Dispose(); $payloadStream = $null }
    if ((Get-Item -LiteralPath $payloadFile).Length -ne [long]$bundle.MsiSize -or
        (Get-FileHash -LiteralPath $payloadFile -Algorithm SHA256).Hash -ne $bundle.MsiHash.ToUpperInvariant()) { throw 'Embedded MSI does not match the Setup integrity metadata.' }
    $windowsInstaller = New-Object -ComObject WindowsInstaller.Installer
    $database = $windowsInstaller.OpenDatabase($payloadFile, 0)
    foreach ($property in @('ProductVersion', 'ProductCode', 'UpgradeCode')) {
        $query = 'SELECT {0}Value{0} FROM {0}Property{0} WHERE {0}Property{0}=''{1}''' -f [char]96, $property
        $view = $database.OpenView($query)
        $record = $null
        try {
            $view.Execute() | Out-Null
            $record = $view.Fetch()
            if (-not $record) { throw "Embedded MSI is missing property $property." }
            $value = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
            if (($property -eq 'ProductVersion' -and $value -ne $Version) -or
                ($property -eq 'ProductCode' -and $value -ne $bundle.ProductCode) -or
                ($property -eq 'UpgradeCode' -and $value -ne $bundle.UpgradeCode)) { throw "Setup identity/version does not match $property." }
        } finally {
            $view.Close() | Out-Null
            if ($record -and [Runtime.InteropServices.Marshal]::IsComObject($record)) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) | Out-Null }
            if ($view -and [Runtime.InteropServices.Marshal]::IsComObject($view)) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) | Out-Null }
        }
    }
} finally {
    if ($database) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null }
    if ($windowsInstaller) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($windowsInstaller) | Out-Null }
    if ($bundleStream) { $bundleStream.Dispose() }
    if ($payloadStream) { $payloadStream.Dispose() }
    if (Test-Path -LiteralPath $payloadFile) { Remove-Item -LiteralPath $payloadFile -Force }
}
if ($Channel -eq 'stable' -and (Get-AuthenticodeSignature -LiteralPath $artifact.FullName).Status -ne 'Valid') { throw 'Stable appcasts require a Windows-trusted Authenticode signature on the complete Setup executable.' }
$key = Get-Item -LiteralPath $PrivateKeyFile
$publicOutput = & $tool public-key --private-key-file $key.FullName
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the update signing key.' }
$public = [regex]::Match(($publicOutput -join "`n"), 'Public key: ([A-Za-z0-9+/]{43}=)').Groups[1].Value
if (-not $public) { throw 'WinSparkle did not return an Ed25519 public key.' }
$signatureOutput = & $tool sign --private-key-file $key.FullName $artifact.FullName
if ($LASTEXITCODE -ne 0) { throw 'Update signing failed.' }
$signature = [regex]::Match(($signatureOutput -join "`n"), '[A-Za-z0-9+/]{86}==').Value
if (-not $signature) { throw 'WinSparkle did not return a signature.' }
& $tool verify --public-key $public --signature $signature $artifact.FullName
if ($LASTEXITCODE -ne 0) { throw 'Update signature verification failed.' }
$outputPath = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $outputPath) { throw 'Refusing to replace an existing appcast.' }
New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($outputPath)) -Force | Out-Null
$options = [Xml.XmlWriterSettings]::new()
$options.Encoding = [Text.UTF8Encoding]::new($false)
$options.Indent = $true
$writer = [Xml.XmlWriter]::Create($outputPath, $options)
$sparkle = 'http://www.andymatuschak.org/xml-namespaces/sparkle'
try {
    $writer.WriteStartDocument()
    $writer.WriteStartElement('rss'); $writer.WriteAttributeString('version','2.0')
    $writer.WriteAttributeString('xmlns','sparkle',$null,$sparkle)
    $writer.WriteStartElement('channel'); $writer.WriteElementString('title',"ARPEON $Channel")
    $writer.WriteStartElement('item'); $writer.WriteElementString('title',"ARPEON $Version")
    $writer.WriteElementString('sparkle','version',$sparkle,$Version)
    $writer.WriteElementString('sparkle','releaseNotesLink',$sparkle,$ReleaseNotesUrl.AbsoluteUri)
    $writer.WriteElementString('pubDate',[DateTimeOffset]::UtcNow.ToString('r',[Globalization.CultureInfo]::InvariantCulture))
    $writer.WriteStartElement('enclosure')
    $writer.WriteAttributeString('url',$DownloadUrl.AbsoluteUri)
    $writer.WriteAttributeString('length',$artifact.Length.ToString([Globalization.CultureInfo]::InvariantCulture))
    $writer.WriteAttributeString('type','application/octet-stream')
    $writer.WriteAttributeString('sparkle','os',$sparkle,'windows-x64')
    $writer.WriteAttributeString('sparkle','edSignature',$sparkle,$signature)
    $writer.WriteAttributeString('sparkle','installerArguments',$sparkle,'--install --autolaunch')
    $writer.WriteEndElement(); $writer.WriteEndElement(); $writer.WriteEndElement(); $writer.WriteEndElement()
    $writer.WriteEndDocument()
} finally { $writer.Dispose() }
$checksum = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$outputPath.installer.sha256" -Value "$checksum  $($artifact.Name)" -Encoding ascii
Write-Host "Local appcast prepared for $Channel; nothing was uploaded. Public key: $public"
