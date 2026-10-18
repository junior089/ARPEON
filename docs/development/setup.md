# Development and Windows packaging

Build prerequisites: Windows x64, JDK 25 (`JAVA_HOME`), Node 24.15+ in the 24.x
line, npm, and a desktop session for native UI acceptance. Python 3 is optional
for the dependency auditor and is never part of the end-user runtime. Gradle
9.8.1, npm lockfiles, Gradle artifact verification and native SHA-256 pins fix
dependency inputs. Network populates build caches; OMR runs offline.

```powershell
cd frontend
npm ci
npm run build
cd ..
./gradlew.bat build --warning-mode=fail
./scripts/package-windows.ps1 -SkipFrontendBuild -OutputSubdirectory dev
```

The package script obtains checksum-verified WiX build tools, administratively
extracts Audiveris during build, applies pinned security substitutions, extracts
Chromium, includes Java and produces a per-user MSI and portable ZIP. Run the
complete `ARPEON/ARPEON.exe` folder or install the MSI. Keep all adjacent files.
No console, external browser or end-user runtime installation is needed.

For incremental development, rebuild the frontend and run current classes using
previously prepared native resources:

```powershell
./gradlew.bat :apps:desktop:run -ParpeonInstallDir=C:/absolute/path/to/dev/ARPEON
```

This development command needs a complete native app image. Ordinary Gradle
ZIP/TAR distributions are developer artifacts, not standalone installers.
Windows is the only packaged platform validated locally; other systems require
native packaging and acceptance, irrespective of the core CI matrix.

`-SkipInstaller`, `-SkipZip`, `-SkipFrontendBuild` and `-SkipDesktopBuild` require
already-current outputs. Existing app images are never replaced; choose another
`-OutputSubdirectory`. MSI timestamps/generated IDs prevent a bit-for-bit
reproducibility claim. Dependencies, source revision, dirty state and runtime
versions are recorded in the package.

MSI output is immutable, including test packages. Never rebuild over the source
of an installed product: Windows SecureRepair can reject the changed unsigned
source. For repeated local acceptance, use a new output directory and the same
fresh `-TestRunId` on both builders and `test-msi-lifecycle.ps1 -TestIdentity`.
Test identities have separate product/upgrade/component IDs, registry and shortcuts.

Interactive installation uses `ARPEON-<version>-Setup.exe`, the custom WPF shell
with the complete MSI embedded. The separate MSI retains native maintenance and
silent installation support. See [installer design and acceptance](installer-design.md)
for building the shell alone and running its isolated lifecycle tests.

`verify-package.ps1 -Package <ARPEON directory>` verifies the file inventory.
MSI/ZIP checksums detect corruption; they do not authenticate an unsigned
publisher. `-RequireSignature` needs `ARPEON_SIGN_CERT_THUMBPRINT`,
`ARPEON_TIMESTAMP_URL`, a trusted code-signing certificate and `signtool.exe`.
`-ForRelease` additionally enforces release-readiness gates. Local packaging scripts do not upload artifacts or publish releases. The separate manual release workflow is
described in updates.md; dispatching it is a publication action.

## Data and diagnostics

New Windows profiles use `%LOCALAPPDATA%/ARPEON`. macOS/Linux path selection is
unit-tested; package support is pending. Existing `%USERPROFILE%/.arpeon`
profiles remain in place without destructive migration. `-Darpeon.dataDir=...`
provides isolated development/acceptance profiles. Projects belong outside the
installation. Uninstall intentionally preserves user data.

Projects retain atomic writes, checksums, backups and recovery. Preferences and
bounded histories use durable replacement. A process lock prevents simultaneous
access by two instances sharing a profile. Logs rotate under `logs`, Chromium
cache lives under `browser`, and temporary OMR jobs are removed after completion,
failure or cancellation. Audiveris settings are isolated per job and text OCR
models come from the package. `ARPEON_AUDIVERIS_EXECUTABLE` is an explicit developer
override, not an installation prerequisite or discovery mechanism.

Microphone permission is always explicit. Enable it in OS privacy settings and
close applications holding the device exclusively. Hardware checks are in
[testing](testing.md). Update setup is in [updates](updates.md); the committed
configuration is inactive. Review [native licensing](../legal/native-distribution.md)
before sharing binary packages publicly.
