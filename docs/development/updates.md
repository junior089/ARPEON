# Windows updates

Windows uses WinSparkle 0.9.4, a maintained native updater, through JDK 25's
Foreign Function and Memory API. Download and version comparison belong to
WinSparkle; the installation transaction belongs to MSI. ARPEON authors its WiX
installer, not a custom file-replacement protocol. Authenticity is checked by Ed25519
signatures; HTTPS protects the appcast and download transport. Authenticode
serves a separate Windows publisher-trust purpose and does not replace Ed25519.
The appcast itself is not a separately signed document verified by WinSparkle.

`packaging/updates.properties` is intentionally inactive: no URL or key exists.
Inactive builds never initialize the DLL or query an endpoint. **Ajuda → Verificar
atualizações** reports that publication has not happened. Enabled configurations
require a real HTTPS URL and 32-byte Base64 public key; malformed configurations
disable updates and log the failure while leaving local music tools available.
Automatic checks are opt-in under **Preferências → Gerenciar atualizações**.
Consent is persisted in update-preferences-<channel>.txt in the user profile;
missing/corrupt preferences disable background checks. Native checks run outside
the application UI at a daily interval. Downloads and installation require a choice.
The native dialog handles manual checks, version/changelog, signed download,
failure and installation, including measured download progress. The application
surface polls native callback state only while its update dialog is open and
does not invent percentages. ARPEON refuses shutdown for updates during OMR,
capture, practice, analysis or unsaved score revisions.

Registry preferences are isolated by channel under
`HKCU\Software\ARPEON\Updates\<channel>`. Stable, beta, nightly and testing
settings must have separate feeds and signing keys. Changing channel is currently
a build configuration, not an end-user preference.
Windows installer/appcast versions use three increasing numeric fields within
MSI limits. Beta/nightly labels belong to their separate channels; a fourth
numeric field is rejected because MSI would ignore it during upgrades.

## Preparing a future publication

1. Resolve all gates in `packaging/release-readiness.json` with reviewable evidence.
2. Generate production keys using the checksum-verified WinSparkle signing tool.
   Keep private keys in a protected secret store; never commit them. Back them up
   securely and document rotation before relying on updates for recovery.
3. Configure real feed/public-key values for a release build. The committed
   default stays inactive. Build and Authenticode-sign the **complete MSI** using
   `package-windows.ps1 -ForRelease`. Sign before creating the appcast.
4. Run `new-update-appcast.ps1` with installer, numeric version, actual HTTPS
   download/release-note URLs, private-key file, channel and output. It signs and
   verifies locally, records the installer checksum, and uploads nothing.
5. Test the testing channel with trusted HTTPS, a disposable Windows profile,
   two installer versions and real projects/preferences. Verify cancellation,
   invalid signatures, interrupted downloads, installation failure, restart and
   data preservation. Do not disable TLS verification to make this pass.
6. The manual release-windows.yml workflow requires the windows-publication
   environment and release evidence. Configure reviewers, secrets
   ARPEON_SIGNING_PFX_BASE64, ARPEON_SIGNING_PASSWORD, ARPEON_UPDATE_PRIVATE_KEY,
   and variable ARPEON_TIMESTAMP_URL. It signs and verifies artifacts, publishes
   an immutable version/channel release and only then updates updates/<channel>.xml
   on the repository's default branch through the GitHub contents API with the previous blob SHA. Concurrent
   edits fail safely; channel versions must increase. The configured feed uses
   raw.githubusercontent.com. This requires a public repository and branch rules
   permitting the publication. The workflow has not run with production credentials.

Example arguments intentionally have no operational-looking sample URL: supply
`-DownloadUrl` and `-ReleaseNotesUrl` from the actual release being prepared.
MSI updates use `/passive /norestart`, showing installation progress. The stable
appcast generator refuses unresolved release gates. Package build arguments
-UpdateChannel, -UpdateFeedUrl and -UpdatePublicKey enable only the built image,
leaving the committed defaults inactive. The appcast generator validates the
MSI product/version; stable also requires Windows-trusted Authenticode.
Updates pass ARPEON_AUTOLAUNCH=1 to restart only after successful InstallFinalize.

Native initialization/cleanup and offline signature verification can be tested
without a feed. They do **not** prove network update or MSI recovery. Rollback
after Windows Installer failure is delegated to MSI; ARPEON project recovery
uses existing atomic writes/backups. Full packages are intentional: this
WinSparkle/MSI distribution has no validated differential-update protocol.
The installer must not migrate user data. Before introducing a new database or
project schema, require compatibility tests and a backup before first write;
binary rollback must not automatically downgrade migrated user data.
The current per-user fault-injection test restored binaries but failed internal
Windows Installer registry rollback on this non-elevated host. The
`installerRecovery` release gate remains false. An exact product/version marker
allows a matching MSI to offer recovery at the registered directory, but this
does not certify recovery from arbitrary registry corruption or bypass Windows
SecureRepair. See production-validation.md for logs and the upstream report.
Compatibility with future schema migrations must be tested for each release.
macOS Sparkle and Linux package mechanisms are
future work; neither is claimed supported.
