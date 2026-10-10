# Setup

Prerequisites: JDK 25 with `JAVA_HOME` set, Node 24.15+ in the 24.x line, npm, a desktop
session with a default browser, and a microphone supported by Java Sound. The wrapper pins
Gradle 9.8.1 and verifies its distribution SHA-256. Use `gradlew.bat` on Windows.

1. Run `npm ci` in `frontend`.
2. Run `npm run build` there.
3. At the repository root run `./gradlew build`.
4. Run `./gradlew :apps:desktop:run`.

The frontend is bundled into desktop resources before running. Rebuild it after UI changes.
The distribution ZIP/TAR is produced under `apps/desktop/build/distributions`; extract and
run its `bin` launcher with JDK 25 installed. It includes local UI assets, licenses and source.
There is no automatic download of application models or user content.

For a portable Windows executable, run `powershell -ExecutionPolicy Bypass -File
scripts/package-windows.ps1` at the repository root. The script builds the frontend and desktop,
then writes `apps/desktop/build/native/ARPEON/ARPEON.exe` and a ZIP beside it. Extract the
entire ZIP before launching: the executable uses the bundled Java runtime and app libraries in
adjacent folders. Keep its console open while using the browser interface; close it or press
**Encerrar ARPEON** in the interface or Ctrl+C in the console stops the server. The package includes the license, third-party notices and corresponding
source archive. The adjacent `.sha256` file records the ZIP checksum. `-SkipFrontendBuild` is
available when the frontend bundle is already current;
`-SkipDesktopBuild` reuses an existing desktop distribution, and `-SkipZip` skips the sharing
archive. If an output folder already exists, use `-OutputSubdirectory rc-name` for a separate
build; the script never replaces an existing app folder. Building requires Windows, JDK 25 and
Node 24, while
running the package does not require Java or Node installed. The local features work offline;
the reviewed Wikimedia import and first-time Audiveris setup need an internet connection.

The desktop launcher allows up to 2 GiB of Java heap for large local MusicXML/OMR work;
files near the 128 MiB MusicXML cap may also need substantial browser and Audiveris memory.

For OMR on 64-bit Windows, open **Importar** and choose **Configurar Audiveris automaticamente**.
After your confirmation, ARPEON downloads the official Audiveris 5.11.0 Windows package from
GitHub, verifies its published SHA-256, and extracts it under `%USERPROFILE%\.arpeon\tools`.
This per-user setup does not register a machine-wide installation and needs no administrator
approval. The first setup needs an internet connection; later recognition works offline. Other
platforms can use an existing Audiveris installation by setting `ARPEON_AUDIVERIS_EXECUTABLE`
before starting ARPEON. Audiveris remains a separate AGPL-3.0 program and is not bundled in the
ARPEON package. Keep the source scan until you have reviewed the recognized score; temporary OMR
files are removed after each run.

Enable microphone access in your OS privacy settings if capture fails. Close applications
holding the device exclusively. Stop capture before selecting another input or reference.
Refresh inputs after reconnecting hardware. A refreshed browser tab reconnects to the running
launcher and restores its open score. If the launcher has restarted, open its new browser window
to establish a new authorized session. No machine-wide setup scripts are provided.
