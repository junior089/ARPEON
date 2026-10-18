param(
    [switch]$SkipFrontendBuild,
    [switch]$SkipDesktopBuild,
    [switch]$SkipZip,
    [switch]$SkipInstaller,
    [switch]$RequireSignature,
    [switch]$ForRelease,
    [string]$OutputSubdirectory = '',
    [ValidateSet('stable','beta','nightly','testing')][string]$UpdateChannel = 'stable',
    [string]$UpdateFeedUrl = '',
    [string]$UpdatePublicKey = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'This package script must run on Windows.'
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($UpdateFeedUrl -or $UpdatePublicKey) {
    $feed = $null
    if (-not [Uri]::TryCreate($UpdateFeedUrl, [UriKind]::Absolute, [ref]$feed) -or
        $feed.Scheme -ne 'https' -or -not $feed.Host -or $feed.UserInfo -or $feed.Fragment -or
        $UpdateFeedUrl.Contains("`r") -or $UpdateFeedUrl.Contains("`n")) { throw 'Updates require a real HTTPS feed without credentials or fragments.' }
    try { $decodedKey = [Convert]::FromBase64String($UpdatePublicKey) } catch { throw 'Invalid update public key.' }
    if ($decodedKey.Length -ne 32) { throw 'Updates require a 32-byte Ed25519 public key.' }
} elseif ($ForRelease) { throw 'A release build requires -UpdateFeedUrl and -UpdatePublicKey.' }
if ($ForRelease) {
    & (Join-Path $PSScriptRoot 'assert-release-ready.ps1')
    $RequireSignature = $true
}
$buildFile = Join-Path $repoRoot 'build.gradle.kts'
$versionMatch = [regex]::Match((Get-Content -LiteralPath $buildFile -Raw), 'version\s*=\s*"([0-9]+(?:\.[0-9]+){1,3})"')
if (-not $versionMatch.Success) {
    throw "Cannot read the application version from $buildFile"
}
$appVersion = $versionMatch.Groups[1].Value
$msiVersion = [Version]::Parse($appVersion)
if ($appVersion -notmatch '^\d+\.\d+\.\d+$' -or $msiVersion.Major -gt 255 -or $msiVersion.Minor -gt 255 -or $msiVersion.Build -gt 65535) {
    throw 'Windows requires major.minor.patch, with major/minor <= 255 and patch <= 65535. MSI ignores a fourth component.'
}
$certificateThumbprint = $env:ARPEON_SIGN_CERT_THUMBPRINT
$timestampUrl = $env:ARPEON_TIMESTAMP_URL
$signingRequested = -not [string]::IsNullOrWhiteSpace($certificateThumbprint) -or
    -not [string]::IsNullOrWhiteSpace($timestampUrl)
if ($RequireSignature -and -not $signingRequested) {
    throw 'Signature required, but ARPEON_SIGN_CERT_THUMBPRINT and ARPEON_TIMESTAMP_URL are not configured.'
}
if ($signingRequested) {
    if ([string]::IsNullOrWhiteSpace($certificateThumbprint) -or
        [string]::IsNullOrWhiteSpace($timestampUrl)) {
        throw 'Configure both ARPEON_SIGN_CERT_THUMBPRINT and ARPEON_TIMESTAMP_URL to sign the executable.'
    }
    if ($certificateThumbprint -notmatch '^[A-Fa-f0-9]{40}$') {
        throw 'ARPEON_SIGN_CERT_THUMBPRINT must contain a 40-character certificate thumbprint.'
    }
    $parsedTimestampUrl = $null
    if (-not [Uri]::TryCreate($timestampUrl, [UriKind]::Absolute, [ref]$parsedTimestampUrl) -or
        $parsedTimestampUrl.Scheme -notin @('https', 'http')) {
        throw 'ARPEON_TIMESTAMP_URL must be an absolute HTTP(S) RFC 3161 timestamp endpoint.'
    }
}

$localJdk = Join-Path $repoRoot '.tools/java/jdk-25.0.4.1+1'
$jdkHome = if (Test-Path -LiteralPath (Join-Path $localJdk 'bin/jpackage.exe')) {
    $localJdk
} elseif ($env:JAVA_HOME) {
    $env:JAVA_HOME
} else {
    throw 'JDK 25 is required. Set JAVA_HOME to a JDK 25 installation.'
}
$jpackage = Join-Path $jdkHome 'bin/jpackage.exe'
$java = Join-Path $jdkHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $jpackage) -or -not (Test-Path -LiteralPath $java)) {
    throw "JDK tools not found under $jdkHome"
}
$javaVersionOutput = & $java --version
$javaVersion = $javaVersionOutput | Select-Object -First 1
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '^openjdk 25\.|^java 25\.') {
    throw "JDK 25 is required; found: $javaVersion"
}
$env:JAVA_HOME = $jdkHome
$env:PATH = "$(Join-Path $jdkHome 'bin');$env:PATH"
$localGradleHome = Join-Path $repoRoot '.tools/gradle-home'
if (-not $env:GRADLE_USER_HOME -and (Test-Path -LiteralPath $localGradleHome)) {
    $env:GRADLE_USER_HOME = $localGradleHome
}

$frontend = Join-Path $repoRoot 'frontend'
$frontendIndex = Join-Path $frontend 'dist/arpeon/browser/index.html'
if (-not $SkipFrontendBuild) {
    $localNode = Join-Path $repoRoot '.tools/node/node-v24.21.0-win-x64'
    if (Test-Path -LiteralPath (Join-Path $localNode 'npm.cmd')) {
        $env:PATH = "$localNode;$env:PATH"
        $node = Join-Path $localNode 'node.exe'
        $npmCli = Join-Path $localNode 'node_modules/npm/bin/npm-cli.js'
    } else {
        $node = (Get-Command node.exe -ErrorAction Stop).Source
        $npmCli = Join-Path (Split-Path $node) 'node_modules/npm/bin/npm-cli.js'
        if (-not (Test-Path -LiteralPath $npmCli)) { throw 'npm CLI not found beside Node.js.' }
    }
    Push-Location $frontend
    try {
        if (-not (Test-Path -LiteralPath (Join-Path $frontend 'node_modules'))) {
            & $node $npmCli ci
            if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
        }
        & $node (Join-Path $frontend 'node_modules/@angular/cli/bin/ng.js') build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path -LiteralPath $frontendIndex)) {
    throw "Frontend bundle missing: $frontendIndex"
}

if (-not $SkipDesktopBuild) {
    Push-Location $repoRoot
    try {
        & $java --enable-native-access=ALL-UNNAMED -classpath (Join-Path $repoRoot 'gradle/wrapper/gradle-wrapper.jar') org.gradle.wrapper.GradleWrapperMain ':apps:desktop:installDist' ':apps:desktop:prepareChromium'
        if ($LASTEXITCODE -ne 0) { throw 'Gradle desktop distribution build failed.' }
    } finally {
        Pop-Location
    }
}

& (Join-Path $PSScriptRoot 'prepare-windows-runtime.ps1') -PrepareInstallerTools:(-not $SkipInstaller)
$cefRoot = Join-Path $repoRoot 'apps/desktop/build/native-resources/cef'
if (-not (Test-Path -LiteralPath (Join-Path $cefRoot 'install.lock'))) { throw 'Run :apps:desktop:prepareChromium before packaging.' }

$appInput = Join-Path $repoRoot 'apps/desktop/build/install/desktop/lib'
$mainJar = "desktop-$appVersion.jar"
if (-not (Test-Path -LiteralPath (Join-Path $appInput $mainJar))) {
    throw "Desktop JAR missing: $(Join-Path $appInput $mainJar)"
}
if (-not (Test-Path -LiteralPath (Join-Path $repoRoot 'apps/desktop/build/source/source.zip'))) {
    throw 'Corresponding source archive missing. Run without -SkipDesktopBuild.'
}

$nativeRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot 'apps/desktop/build/native'))
if ((Test-Path -LiteralPath $nativeRoot) -and
    (((Get-Item -LiteralPath $nativeRoot -Force).Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)) {
    throw "Refusing to package through a reparse point: $nativeRoot"
}
if ($OutputSubdirectory -and $OutputSubdirectory -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$') {
    throw 'OutputSubdirectory must be one simple folder name.'
}
$outputDir = if ($OutputSubdirectory) {
    [System.IO.Path]::GetFullPath((Join-Path $nativeRoot $OutputSubdirectory))
} else {
    $nativeRoot
}
$imageDir = [System.IO.Path]::GetFullPath((Join-Path $outputDir 'ARPEON'))
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
if (((Get-Item -LiteralPath $outputDir -Force).Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "Refusing to package through a reparse point: $outputDir"
}
if (Test-Path -LiteralPath $imageDir) {
    throw "Output already exists: $imageDir. Use a new -OutputSubdirectory for a side-by-side build."
}

& $jpackage `
    --type app-image `
    --name ARPEON `
    --app-version $appVersion `
    --description 'ARPEON — partituras, edição e estudo musical' `
    --vendor ARPEON `
    --icon (Join-Path $repoRoot 'packaging/arpeon.ico') `
    --input $appInput `
    --main-jar $mainJar `
    --main-class org.arpeon.desktop.DesktopApplication `
    --java-options '-Xmx2g' `
    --java-options '-Dfile.encoding=UTF-8' `
    --java-options '--enable-native-access=ALL-UNNAMED' `
    --add-modules 'java.se,jdk.httpserver,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.unsupported' `
    --dest $outputDir
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }

$launcher = Join-Path $imageDir 'ARPEON.exe'
if (-not (Test-Path -LiteralPath $launcher)) {
    throw "Windows launcher missing: $launcher"
}
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination (Join-Path $imageDir 'LICENSE')
Copy-Item -LiteralPath (Join-Path $repoRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $imageDir 'THIRD_PARTY_NOTICES.md')
Copy-Item -LiteralPath (Join-Path $repoRoot 'apps/desktop/build/source/source.zip') -Destination (Join-Path $imageDir 'source.zip')
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs/development/windows-portable.txt') -Destination (Join-Path $imageDir 'LEIA-ME.txt')
$releaseNotes = Join-Path $repoRoot "docs/releases/$appVersion-windows.md"
if (-not (Test-Path -LiteralPath $releaseNotes -PathType Leaf)) { throw "Release notes missing for version $appVersion." }
Copy-Item -LiteralPath $releaseNotes -Destination (Join-Path $imageDir 'RELEASE-NOTES.md')
$toolsDir = Join-Path $imageDir 'tools'
New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
foreach ($component in @('cef', 'audiveris-5.11.0', 'updater')) {
    Copy-Item -LiteralPath (Join-Path $repoRoot "apps/desktop/build/native-resources/$component") -Destination $toolsDir -Recurse
}
$configDir = Join-Path $imageDir 'config'
New-Item -ItemType Directory -Path $configDir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repoRoot 'packaging/updates.properties') -Destination $configDir
if ($UpdateFeedUrl) {
    $escapedFeed = $UpdateFeedUrl.Replace('\', '\\')
    [IO.File]::WriteAllText((Join-Path $configDir 'updates.properties'), "enabled=true`nchannel=$UpdateChannel`nappcastUrl=$escapedFeed`npublicKey=$UpdatePublicKey`n", [Text.UTF8Encoding]::new($false))
}
Copy-Item -LiteralPath (Join-Path $repoRoot 'packaging/native-lock.json') -Destination $imageDir

if ($signingRequested) {
    $signToolCommand = Get-Command 'signtool.exe' -ErrorAction Stop
    & $signToolCommand.Source sign /sha1 $certificateThumbprint /s My /fd SHA256 /tr $timestampUrl /td SHA256 $launcher
    if ($LASTEXITCODE -ne 0) { throw 'Authenticode signing failed.' }
    & $signToolCommand.Source verify /pa /v $launcher
    if ($LASTEXITCODE -ne 0) { throw 'Authenticode signature verification failed.' }
    $authenticode = Get-AuthenticodeSignature -LiteralPath $launcher
    if ($authenticode.Status -ne 'Valid') {
        throw "Windows does not trust the signed launcher: $($authenticode.Status)"
    }
    Write-Host "Authenticode signature verified: $($authenticode.SignerCertificate.Thumbprint)"
} else {
    Write-Warning 'The Windows package is unsigned. Configure signing variables for a release build.'
}

& (Join-Path $PSScriptRoot 'write-package-manifest.ps1') -Package $imageDir -Version $appVersion -PublicDistributionApproved:$ForRelease

if (-not $SkipInstaller) {
    & (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -Package $imageDir -Version $appVersion -OutputDirectory $outputDir -SkipBootstrapper
    $msiPath = Join-Path $outputDir "ARPEON-$appVersion.msi"
    if ($signingRequested) {
        & $signToolCommand.Source sign /sha1 $certificateThumbprint /s My /fd SHA256 /tr $timestampUrl /td SHA256 $msiPath
        if ($LASTEXITCODE -ne 0) { throw 'Installer signing failed.' }
        & $signToolCommand.Source verify /pa /v $msiPath
        if ($LASTEXITCODE -ne 0) { throw 'Installer signature verification failed.' }
    }
    $msiHash = (Get-FileHash -LiteralPath $msiPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$msiPath.sha256" -Value "$msiHash  $(Split-Path -Leaf $msiPath)" -Encoding ascii
    Write-Host "Windows installer: $msiPath"
    $setupPath = Join-Path $outputDir "ARPEON-$appVersion-Setup.exe"
    # Embed the final signed MSI so its integrity hash matches the distributed payload.
    & (Join-Path $PSScriptRoot 'build-installer-shell.ps1') -Installer $msiPath -Package $imageDir -Output $setupPath
    if ($signingRequested) {
        & $signToolCommand.Source sign /sha1 $certificateThumbprint /s My /fd SHA256 /tr $timestampUrl /td SHA256 $setupPath
        if ($LASTEXITCODE -ne 0) { throw 'Setup signing failed.' }
        & $signToolCommand.Source verify /pa /v $setupPath
        if ($LASTEXITCODE -ne 0) { throw 'Setup signature verification failed.' }
    }
    $setupHash = (Get-FileHash -LiteralPath $setupPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$setupPath.sha256" -Value "$setupHash  $(Split-Path -Leaf $setupPath)" -Encoding ascii
}

if (-not $SkipZip) {
    $zipPath = Join-Path $outputDir "ARPEON-$appVersion-windows-x64.zip"
    Compress-Archive -LiteralPath $imageDir -DestinationPath $zipPath -CompressionLevel Optimal -Force
    $checksum = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$zipPath.sha256" -Value "$checksum  $(Split-Path -Leaf $zipPath)" -Encoding ascii
    Write-Host "Portable ZIP: $zipPath"
    Write-Host "SHA-256: $checksum"
}
Write-Host "Windows executable: $launcher"
