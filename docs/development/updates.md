# Windows updates

Windows uses WinSparkle 0.9.4, a maintained native updater, through JDK 25's
Foreign Function and Memory API. Download and version comparison belong to
WinSparkle; the installation transaction belongs to MSI, launched by the signed
ARPEON Setup executable. Setup owns recovery if an MSI upgrade fails after the
older product is removed. Public appcasts therefore point to the complete Setup
executable, not the embedded MSI. Authenticity is checked by Ed25519
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
   default stays inactive. Build and Authenticode-sign the **complete Setup.exe**
   using `package-windows.ps1 -ForRelease`. Sign before creating the appcast.
4. Run `new-update-appcast.ps1` with installer, numeric version, actual HTTPS
    download/release-note URLs, private-key file, channel and output. It signs and
    verifies locally, records the Setup checksum, validates the embedded MSI,
    and uploads nothing.
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
WinSparkle launches Setup with `--install --autolaunch`; Setup shows progress and
restarts ARPEON only after a successful install. The stable appcast generator
refuses unresolved release gates. Package build arguments
-UpdateChannel, -UpdateFeedUrl and -UpdatePublicKey enable only the built image,
leaving the committed defaults inactive. The appcast generator validates the
Setup and embedded MSI identity/version; stable also requires Windows-trusted
Authenticode on the complete Setup executable.

Native initialization/cleanup and offline signature verification can be tested
without a feed. They do **not** prove network update or MSI recovery. Failed
upgrades restore the verified cached previous MSI through Setup. MSI removes the
previous product before beginning the new install because its per-user
registration rollback is unreliable on affected Windows hosts. Release feeds
must never bypass Setup by serving the MSI directly. ARPEON project recovery
uses existing atomic writes/backups. Full packages are intentional: this
WinSparkle/MSI distribution has no validated differential-update protocol.
The installer must not migrate user data. Before introducing a new database or
project schema, require compatibility tests and a backup before first write;
binary rollback must not automatically downgrade migrated user data.
The isolated fault-injection acceptance now verifies failed-upgrade recovery of
the previous product registration, marker and every inventoried payload file.
It does not certify a clean Windows VM, network update, arbitrary registry
corruption or Windows SecureRepair behavior. See production-validation.md for
the result and remaining release gates.
Compatibility with future schema migrations must be tested for each release.
macOS Sparkle and Linux package mechanisms are
future work; neither is claimed supported.
