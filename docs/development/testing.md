# Testing

Run all commands in README's validation block. Java `build` includes unit and integration
tests, compiler `-Xlint:all -Werror`, and google-java-format checks. Angular uses its Vitest builder,
strict compilation, ESLint (including templates/accessibility) and Prettier. Tests are run
serially at the frontend file level for reproducible Windows temporary-file handling.

Java tests cover domain invariants, exact duration, written/sounding pitch, MusicXML fixture
import, XXE/DOCTYPE/depth/size limits, unsupported semantics, synthetic YIN accuracy, silence,
DC/noise, tuner stability/lifecycle/backpressure, HTTP contracts, unauthorized origins,
consent, bounded request size and automatic microphone release on lost UI polling.
Milestone 1 tests cover exact tick/second mapping across tempo and meter changes, part timing
conflicts, MIDI note/metronome/count-in sequence placement, score bridge authorization and
input limits, score session response validation and explicit BPM application. Browser checks
must confirm visible SVG notation, cursor seek and transport controls with the original
fixture. Listening quality and cross-platform MIDI devices require manual validation.

Milestone 2 regression tests cover rest-preserving insert/delete/duration edits, immutable
undo/redo, explicit-key transposition and enharmonic spelling, flute range previews, the
flute/clarinet MIDI programs, MusicXML key and instrument round-trips, and the authenticated
preview/apply/export HTTP flow. On real Windows, macOS and Linux audio devices, also confirm
that the flute program is audible, that its timbre is distinct from the generic voice, and
that unavailable MIDI output produces the visible error rather than silent success. Record
the device and synthesizer; automated MIDI event tests do not establish acoustic quality.

Frontend tests also check runtime response validation, permission failures, malformed readings,
explicit consent and stop behavior for the standalone tuner.

Milestone 3 tests cover score-domain exercise generation, scale intervals, flute range
rejection, note and long-tone feedback, rhythm tap timing, local summary persistence and an
authenticated synthetic-microphone HTTP flow. Real instrument audio, loop audibility,
fingering readability on small screens and input-to-feedback latency require manual checks.

Milestone 4 tests cover missing rhythm/octave in pasted notes, ambiguous and hostile tokens,
exact MIDI gaps, polyphony rejection, WAV pitch estimation, authenticated preview/accept and
explicit assumption acknowledgement. Real recordings, diverse MIDI exporters, browser layout
and import throughput on larger corpora remain to be validated; no bulk-speed claim is made.

Synthetic accuracy tolerances allow finite-window/interpolation error, remaining below the
UI's ±5-cent region. Test doubles exist only in test sources; the application always uses
Java Sound. No test pretends a live device was validated.

## Manual hardware acceptance matrix

Run on Windows, macOS and Linux with actual devices. Record OS/JDK, device, selected rate,
permission result, measured latency and observed failures without including private paths.

- Permit/deny access; enumerate zero, one and multiple devices; select and switch inputs.
- Sustain a reference tone and musical notes; compare frequency/cents with a known reference.
- Check silence, background noise, unstable attacks and overtone-rich playing.
- Unplug/replug the input; stop during capture; repeat start/stop; close the tab/launcher.
- Confirm no remaining capture indicator, background thread or audio file after stop.
- Test keyboard navigation, visible focus, zoom and narrow window layouts.

CI configures a three-OS matrix but its existence is not evidence that remote runs passed.
