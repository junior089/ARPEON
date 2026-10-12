# Testing

Run all commands in README's validation block. Java `build` includes unit and integration
tests, compiler `-Xlint:all -Werror`, and google-java-format checks. Angular uses its Vitest builder,
strict compilation, ESLint (including templates/accessibility) and Prettier. Tests are run
serially at the frontend file level for reproducible Windows temporary-file handling.

Java tests cover domain invariants, exact duration, written/sounding pitch, cross-measure tie
continuity and removal, sustained MIDI note-off timing, MusicXML tie round-trips, XXE/DOCTYPE/
depth/element-count/size limits, unsupported semantics, synthetic YIN accuracy, silence,
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

Milestone 5 tests cover provider eligibility before network access, a single Commons API and
media fetch through deterministic fixtures, file integrity, content-type and host checks,
provider throttling, and the authenticated local URL endpoint. The separate
[diagnostic benchmark](source-import-benchmark.md) reports filtering, mock fetch and MIDI
parser costs; it does not establish live Commons latency or bulk throughput.

Milestone 6 tests cover PDF/image preflight, hostile or misleading file rejection, bounded
MusicXML normalization, chord and independent-voice preservation with cursor offsets,
rejection of repeats/entities, and the authenticated
preview/acknowledge/open flow with a simulated OMR result. The opt-in production acceptance
runner additionally exercises real bundled Audiveris PDF/PNG recognition, compares pitches,
opens the result in the score workspace, rejects a corrupt PDF and cancels a native process.
The native UI runner also selects an actual PDF through Angular, waits for OMR, checks the
rendered original, acknowledges the review, opens the recognized SVG and verifies autosave
as a separate project. It waits for enabled controls rather than racing backend writes.
See [production validation](production-validation.md); the small original fixture is not
a representative scan corpus or a hardware-performance benchmark.

Milestone 7 tests cover deterministic pitch-window segmentation, fixed-tempo alignment,
separate metrics and missing-data handling. Device onset accuracy, repeated same-pitch attacks,
microphone latency and performance under live acoustic noise need hardware validation.

Milestone 8 tests cover two-window confirmation, pause holding, nearby missed notes,
two-note distant recovery, weak windows, and the authenticated live-mode HTTP contract.
Position accuracy on real hardware, repeated notes, tempo variation, and ambiguous motifs
still need instrument and microphone validation.

Milestone 9 compares the deterministic follower with a learned transition model on disjoint
seeded synthetic traces. The benchmark records position accuracy, recovery, false jumps and
latency. It is not a substitute for rights-cleared real-performance validation; the candidate
is not enabled in the app. Local import caps are exercised at parser boundaries, while the
large-file UI paths still need device-specific memory and throughput checks.

Synthetic accuracy tolerances allow finite-window/interpolation error, remaining below the
UI's ±5-cent region. Test doubles exist only in test sources; the application always uses
Java Sound. No test pretends a live device was validated.

## Manual hardware acceptance matrix

The manual Windows preparation workflow also builds two isolated MSI fixtures
with a dedicated UpgradeCode, product names, component GUIDs, registry paths and
shortcut names. It exercises same-version installation, missing-file repair,
automatic discovery of a Unicode/custom directory, upgrade, refused downgrade,
an injected failure after removal of the old product, rollback, uninstall and
reinstall. Real MusicXML/OMR projects and preferences supply the preservation
hashes. An unowned file placed inside the install directory must also survive.
The second fixture changes installer metadata, not the application's musical
code; this does not prove a signed network update between two public versions.
Use build-windows-installer.ps1 -TestIdentity and test-msi-lifecycle.ps1
-TestIdentity. Fault fixtures belong only under apps/desktop/build and must never
be signed or distributed. The installer UI runner verifies accessible controls,
captures the real native window and cancels without installing. A successful
local lifecycle is not a clean-machine certification.

For a repeated run on the same local host, supply a fresh matching `-TestRunId`
to both builders and the lifecycle runner. Never overwrite an installed MSI's
source. Rollback acceptance checks Windows Installer ProductState and skipped
registry errors in addition to restored files. The 12/10/2026 normal cycle passed;
the separate injected rollback failed internal registration and blocks release.
See production-validation.md for exact evidence and limitations.

Run on Windows, macOS and Linux with actual devices. Record OS/JDK, device, selected rate,
permission result, measured latency and observed failures without including private paths.
Use the blank [hardware acceptance report](hardware-acceptance.md) for each tested device
combination. An unfilled template is a test procedure, not evidence of successful validation.

- Permit/deny access; enumerate zero, one and multiple devices; select and switch inputs.
- Sustain a reference tone and musical notes; compare frequency/cents with a known reference.
- Check silence, background noise, unstable attacks and overtone-rich playing.
- Unplug/replug the input; stop during capture; repeat start/stop; close the tab/launcher.
- Confirm no remaining capture indicator, background thread or audio file after stop.
- Test keyboard navigation, visible focus, zoom and narrow window layouts.

CI configures a three-OS matrix but its existence is not evidence that remote runs passed.
