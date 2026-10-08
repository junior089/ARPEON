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

Enable microphone access in your OS privacy settings if capture fails. Close applications
holding the device exclusively. Stop capture before selecting another input or reference.
Refresh inputs after reconnecting hardware. A refreshed browser tab needs a relaunch because
the session token is intentionally memory-only. No machine-wide setup scripts are provided.
