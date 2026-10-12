# Native desktop distribution and OMR boundary

Status: implemented for internal Windows x64 validation; public-release gates pending.

The Java musical modules and Angular UI already have real behavior and tests.
Replacing them would impose migration risk without a concrete product benefit.
Keep their authenticated ephemeral loopback bridge and embed the UI in a shipped
windowed Chromium engine through JCEF Maven 152.0.6. This avoids relying on the
installed browser/WebView version and retains the existing renderer and editor.
The cost is a larger installer, native initialization and ongoing Chromium
security maintenance. Remote navigation and popups do not enter the app's origin;
external HTTPS links open in the system browser. There is no exposed JavaScript-to-Java bridge.

Audiveris' supported batch CLI is the integration boundary. It is Java with
native OCR/image dependencies and application-specific state; loading its internal
classes as a library would couple musical domain code to upstream application
internals. Ship a pinned executable/runtime assembly instead, export uncompressed
MusicXML and pass that through the existing secure importer and explicit OMR review.
Run on a worker, drain bounded logs, apply timeout, cancel only its process tree,
isolate settings and remove temporary jobs. No separate end-user installation.
Dependency substitutions and license/source restrictions are recorded separately.

Use jpackage for the windowed executable/Java image and explicitly authored WiX 3.14.1/MSI for per-user installation with the existing stable UpgradeCode.
Application files default to `%LOCALAPPDATA%/ARPEON Desktop`; new user data is
`%LOCALAPPDATA%/ARPEON`. Existing `~/.arpeon` stays untouched. Installer updates
must not migrate/delete user data implicitly. Native runtime hashes, Gradle/npm
locks, corresponding application/upstream source, notices and build provenance
accompany the package. Tests define the supported platform, not configuration files.

The authored MSI replaces the default UI, early removal and recursive directory cleaner. RemoveExistingProducts runs after InstallInitialize, inside the rollback transaction. Individual components remove their owned files; RemoveFolder only removes empty directories. Registry identity is corroborated by Windows Installer related-product/version detection; legacy 0.1.0 also has a lookup of its fixed uninstall product identifier. Ambiguous registrations, unknown locations, same-version packages with different identities and downgrades fail before mutation. Same-product maintenance offers launch, repair and removal. Upgrades retain the registered installation path. MSI validation is enabled; only ICE91 is suppressed because this product intentionally has no machine-wide mode. Application data is never owned by MSI.

Use WinSparkle 0.9.4 for signed Windows updates through JDK FFM, avoiding another
native-binding dependency and a custom update protocol. This thin binding requires
native acceptance and exact pinned C signatures. Keep it inactive without a real
feed/key. Automatic checking is opt-in in Preferences, persisted separately by channel and limited to daily checks. Download and installation remain user initiated.
No public release is inferred from successful compilation or internal MSI assembly.
