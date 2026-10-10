param(
    [switch]$SkipFrontendBuild,
    [switch]$SkipDesktopBuild,
    [switch]$SkipZip,
    [switch]$RequireSignature,
    [string]$OutputSubdirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'This package script must run on Windows.'
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildFile = Join-Path $repoRoot 'build.gradle.kts'
$versionMatch = [regex]::Match((Get-Content -LiteralPath $buildFile -Raw), 'version\s*=\s*"([0-9]+(?:\.[0-9]+){1,3})"')
if (-not $versionMatch.Success) {
    throw "Cannot read the application version from $buildFile"
}
$appVersion = $versionMatch.Groups[1].Value
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
        $npm = Join-Path $localNode 'npm.cmd'
    } else {
        $npm = (Get-Command npm.cmd -ErrorAction Stop).Source
    }
    Push-Location $frontend
    try {
        if (-not (Test-Path -LiteralPath (Join-Path $frontend 'node_modules'))) {
            & $npm ci
            if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
        }
        & $npm run build
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
        & (Join-Path $repoRoot 'gradlew.bat') ':apps:desktop:installDist'
        if ($LASTEXITCODE -ne 0) { throw 'Gradle desktop distribution build failed.' }
    } finally {
        Pop-Location
    }
}

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
    --description 'ARPEON local music practice' `
    --vendor ARPEON `
    --input $appInput `
    --main-jar $mainJar `
    --main-class org.arpeon.desktop.DesktopApplication `
    --java-options '-Xmx2g' `
    --java-options '-Dfile.encoding=UTF-8' `
    --add-modules 'java.se,jdk.httpserver,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.unsupported' `
    --win-console `
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
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs/releases/0.1.0-windows.md') -Destination (Join-Path $imageDir 'RELEASE-NOTES.md')

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

if (-not $SkipZip) {
    $zipPath = Join-Path $outputDir "ARPEON-$appVersion-windows-x64.zip"
    Compress-Archive -LiteralPath $imageDir -DestinationPath $zipPath -CompressionLevel Optimal -Force
    $checksum = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$zipPath.sha256" -Value "$checksum  $(Split-Path -Leaf $zipPath)" -Encoding ascii
    Write-Host "Portable ZIP: $zipPath"
    Write-Host "SHA-256: $checksum"
}
Write-Host "Windows executable: $launcher"
