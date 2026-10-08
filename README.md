# ARPEON

Local-first, open-source music practice software. License: **AGPL-3.0-or-later**.

Milestone 4 adds a local import review flow for pasted note names and melodic cipher,
monophonic MIDI, and a short monophonic WAV prototype. Its preview distinguishes source
timing from estimates and missing rhythm. The user must acknowledge assumptions before a
draft enters the score editor. See [import capabilities](docs/product/import-capabilities.md)
for supported syntax and limits. Web adapters remain planned for Milestone 5.

Milestone 3 adds a flute practice area with generated notes, long tones, scales, arpeggios and
basic rhythm. Exercises use the score domain and flute MIDI voice. Live microphone feedback
shows pitch deviation, confidence, response time and long-tone stability; rhythm taps show
timing error. Loop playback can raise its BPM between repetitions. A CC0 fingering
chart is available offline. Up to 50 summaries persist locally without audio. See
[beginner practice](docs/product/beginner-mode.md) for limits and data provenance.

Milestone 2 adds editable score revisions to the local workspace. Open a supported MusicXML
file, select an event by part and measure, change pitch or duration, insert notes into rests,
remove notes, and undo/redo. Transformations preview note and range changes before applying:
semitone interval, octave, explicitly selected source/target key, and written/sounding pitch
for the chosen instrument. Export the current score as MusicXML. The original file is never
modified; the workspace still lives in memory, so export before closing.

The score renderer and MIDI transport support part selection, play/pause/stop/seek, BPM,
mute/solo, synchronized cursor, metronome and count-in. Flute has a selectable General MIDI
voice and range warnings. B-flat clarinet demonstrates instrument transposition through the
same profile mechanism. The actual sound depends on the local MIDI synthesizer and audio
device; no recorded flute sample is bundled.

The standalone chromatic tuner remains available: Java Sound microphone capture,
YIN analysis and an Angular display served only on this computer. No account, telemetry,
audio upload or recording. A4 is configurable from 400 to 480 Hz. Detection covers
50–2000 Hz for one sustained note at a time.

The MusicXML importer is deliberately narrow and rejects unsupported semantics instead of
inventing or dropping music. OMR, ML and web-source adapters are **not implemented**.
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
Open a local `.musicxml` or `.xml` file in **Partitura**. Select a part's flute timbre to hear
its MIDI voice. Use the editor below the score; review a transformation before applying it,
then choose **Exportar MusicXML** to save your revision. Press Play for local MIDI output;
the operating system must provide a MIDI synthesizer/audio device. Choose an input in
**Importar** to paste a note sequence or analyze a supported MIDI/WAV file, review warnings,
choose any missing octave or duration, and create a draft in **Partitura**. Choose an input in
**Prática**, generate an exercise, turn on the microphone, start an attempt and save its
summary when finished. Rhythm also accepts button taps. Or choose an input in
**Afinador** and click
**Ligar microfone**. System microphone permission may be required.
Click **Desligar microfone** to release the device. Closing or navigating away from the tuner
releases capture; if the UI disappears abruptly, the bridge stops capture after a five-second
lease (checked once per second). Stop the launcher with Ctrl+C to close the application.
After refreshing a tab, relaunch the application to obtain a new authorized UI session.

This foundation uses a local browser window, not a packaged native webview or installer.
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
