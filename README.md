# ARPEON

Local-first, open-source music practice software. License: **AGPL-3.0-or-later**.

Milestone 9 includes an offline evaluation of a learned score-position model against the
deterministic follower. The experimental model stays out of the app after its benchmark showed
more false jumps; see the [evaluation](docs/analysis/ml-evaluation.md). Local import limits are
substantially higher; see [format and resource limits](docs/product/import-capabilities.md).

Milestone 8 adds live monophonic note following for one selected score part. Two stable pitch
windows confirm a note; silence holds the position, and a distant jump needs two consecutive
score notes before recovery. The local session ends on request or after twenty minutes. The live
view shows the notation cursor and a note sequence. See
[following limits](docs/analysis/following.md).

Milestone 7 adds a bounded, monophonic performance attempt for one selected score part.
The local microphone supplies pitch windows; after playing at the written tempo, the app
reports separate pitch, timing, rhythm, intonation, missed/extra and confidence measures.
See [scoring formulas and limits](docs/analysis/scoring.md). Fixed-tempo reports are kept
locally by score revision; live-follow positions remain temporary. The fixed-tempo analysis
remains available as a separate mode.

Milestone 4 adds a local import review flow for pasted note names and melodic cipher,
monophonic MIDI, and a short monophonic WAV prototype. Its preview distinguishes source
timing from estimates and missing rhythm. The user must acknowledge assumptions before a
draft enters the score editor. See [import capabilities](docs/product/import-capabilities.md)
for supported syntax and limits.

Milestone 5 adds a reviewed single-URL path for public MIDI files on Wikimedia Commons using
its official API. The import preview shows provenance and declared rights before acceptance.
MuseScore stays manual/export and Cifra Melódica stays paste-only. See the
[provider access review](docs/legal/provider-registry.md).
An [offline diagnostic benchmark](docs/development/source-import-benchmark.md) records
filtering, simulated fetch and parser measurements without claiming live network speed.

Milestone 3 adds a flute practice area with generated notes, long tones, scales, arpeggios and
basic rhythm. Exercises use the score domain and flute MIDI voice. Live microphone feedback
shows pitch deviation, confidence, response time and long-tone stability; rhythm taps show
timing error. Loop playback can raise its BPM between repetitions. A CC0 fingering
chart is available offline. Up to 5000 summaries persist locally without audio. See
[beginner practice](docs/product/beginner-mode.md) for limits and data provenance.

Milestone 2 adds editable score revisions to the local workspace. Open a supported MusicXML
file (`.musicxml`, `.xml`, `.mxl` or single-staff `.mscz`), select an event by part and measure, change pitch or duration, insert notes into rests,
remove notes, and undo/redo. Transformations preview note and range changes before applying:
semitone interval, octave, explicitly selected source/target key, and written/sounding pitch
for the chosen instrument. Export the current score as MusicXML. The original file is never
modified. Use **Salvar projeto** to keep an editable local project; later edits to that saved
project are saved automatically. A newly opened score remains unsaved until you save it as a
project or export its MusicXML.

The score renderer and MIDI transport support part selection, play/pause/stop/seek, BPM,
mute/solo, synchronized cursor, metronome and count-in. Flute has a selectable General MIDI
voice and range warnings. B-flat clarinet demonstrates instrument transposition through the
same profile mechanism. The actual sound depends on the local MIDI synthesizer and audio
device; no recorded flute sample is bundled.

The standalone chromatic tuner remains available: Java Sound microphone capture,
YIN analysis and an Angular display served only on this computer. No account, telemetry,
audio upload or recording. A4 is configurable from 400 to 480 Hz. Detection covers
50–2000 Hz for one sustained note at a time.

The MusicXML importer preserves simultaneous chord heads, independent sequential voices and
bounded non-nested repeats with pass-specific endings. Written measures remain unchanged;
playback uses a separate execution map. Nested repeats/endings and unsupported semantics are
rejected instead of being flattened. Chords can be edited by note head and voices by voice ID;
live analysis remains monophonic.
MXL archives are bounded and only their first declared score is imported; embedded media is not
retained. The experimental ML model is **not integrated into the app**.
Practice feedback reports measurements, without a single grade.

## Run

Install JDK 25 and Node 24.15 or newer in the Node 24 line. Set `JAVA_HOME` to JDK 25.

```sh
cd frontend
npm ci
npm run build
cd ..
./gradlew :apps:desktop:run
```

On Windows use `gradlew.bat` instead of `./gradlew`. The launcher opens the default browser.
To build a portable Windows `.exe` with Java included, run
`powershell -ExecutionPolicy Bypass -File scripts/package-windows.ps1` at the repository root.
Extract `apps/desktop/build/native/ARPEON-0.1.0-windows-x64.zip` and open `ARPEON.exe` inside
the extracted `ARPEON` folder. Keep its console open during use; closing it stops the local app.
Verify the adjacent `.sha256` checksum before sharing the ZIP. See
[Windows packaging](docs/development/setup.md) for build requirements and
[release notes](docs/releases/0.1.0-windows.md) for current limitations.
Open a local `.musicxml`, `.xml`, `.mxl` or single-staff `.mscz` file in **Partitura**. Select a part's flute timbre to hear
its MIDI voice. Use the editor below the score; review a transformation before applying it,
then choose **Exportar MusicXML** to save your revision. Press Play for local MIDI output;
the operating system must provide a MIDI synthesizer/audio device. Choose an input in
**Importar** to paste a note sequence or analyze a supported MIDI/WAV file, review warnings,
or paste a Wikimedia Commons MIDI file-page URL and provide an API contact. Review its source,
rights and warnings, choose any missing octave or duration, and create a draft in **Partitura**.
For PDF/image recognition on Windows, select **Configurar Audiveris automaticamente** in
**Importar** the first time. ARPEON downloads the official package, shows download progress,
checks its SHA-256 and prepares it in your local user folder. Then select the PDF/image, compare the OMR preview with
the original, acknowledge uncertainty, and edit the resulting score in **Partitura**.
Choose an input in
**Prática**, generate an exercise, turn on the microphone, start an attempt and save its
summary when finished. Rhythm also accepts button taps. Or choose an input in
**Afinador** and click
**Ligar microfone**. System microphone permission may be required.
Click **Desligar microfone** to release the device. Closing or navigating away from the tuner
releases capture; if the UI disappears abruptly, the bridge stops capture after a five-second
lease (checked once per second). Refreshing the tab reconnects to the running launcher and
restores its open score. Use **Encerrar ARPEON** in the interface or Ctrl+C in the launcher
console to stop the server; after a restart, open its new browser window to establish a session.

This foundation uses a local browser window; the portable Windows executable is not a native
webview or installer.
There are no external requests during normal score or tuner use. Initial dependency installation
requires network access; cached dependencies permit offline builds.

## Validate

```sh
cd frontend
npm run format:check
npm run lint
npm run typecheck
npm test
npm run build
cd ..
node scripts/check-licenses.mjs
./gradlew build --warning-mode=fail
```

The Gradle build includes Java tests, compilation with all compiler lint warnings treated
as errors, formatting checks, and desktop distributions containing the frontend and source archive.
Tests use synthetic audio, not a live microphone. Hardware verification is separate.

See [setup](docs/development/setup.md), [testing](docs/development/testing.md),
[architecture](docs/architecture/overview.md), [roadmap](docs/product/roadmap.md),
[limitations](docs/product/import-capabilities.md)
and [third-party notices](THIRD_PARTY_NOTICES.md).
