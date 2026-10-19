# ADR 0008 — Multi-instrument rehearsal

Status: accepted.

## Decision

Extend the existing immutable `Score` / `Part` document, exact `ScoreTimeline`,
execution map and single Java Sound sequencer. Part IDs survive renaming,
reordering, replacement and persistence. Imported parts receive new IDs to avoid
collisions between separately exported files. Composer, per-measure clefs and
simple tuplet ratios and boundaries remain musical data through edits and export.
Up to eight staves belong to one instrument. Note heads (including cross-staff
chords), rests and per-staff clef changes retain their staff assignments through
pitch/duration edits, part alignment, export and reopening. Staff count is fixed
at the start of a part; voice IDs remain independent of staff numbers.

Part selection controls notation independently of mute, solo and volume. Full,
individual and focus views filter only the renderer's input. The canonical
MusicXML continues to contain every part. The existing explorer owns management;
the collapsible mixer sits immediately above the existing transport.

The receiver applies individual gain multiplied by master through MIDI expression,
including to sustained notes. It reapplies gain and pan after sequencer controller
chasing/resets. Mute wins over solo; multiple solos form a union; clearing the last
solo restores the independently retained mute states. Presets are explicit,
one-time operations and do not bind future visual selection to the mixer.
Live input coalesces pending values per control and always sends the last value.
MIDI expression changes ramp over 30 ms while playing; initial/stopped levels are
applied before notes start. Twelve named mixes per composition can be saved,
updated, applied and deleted without changing transport position or visual focus.

Sounds use the existing local Java Sound synthesizer and its installed/default
General MIDI bank, or a user-imported SF2/DLS bank. There is no new package, network
sound download or redistributed sound asset. Up to eight local banks of 128 MiB
each are stored by SHA-256; a project keeps the bank hash, not sample bytes or an
external filesystem path. Missing/modified banks remain visibly unavailable until
the user imports the same file or chooses another bank. Actual patch names and
per-part loading results are exposed separately from recognized
instrument metadata. A missing patch silences that channel, reports an error and
can be retried without losing notation or affecting other channels. Closing a
player releases its devices. Runtime licenses remain in `runtime/legal`.

## Import and storage

Stage an import before changing the composition. Automatic alignment requires
equal written measure counts, meters, pickup lengths, compatible tempos and
repeat/ending structure. Printed measure labels are not alignment evidence.
Manual anchors map written measure positions, insert explicit rests before/between
source material and reject overlap, clipping, conflicting tempos, incompatible
meters/pickups and interrupted ties. The original file is never modified.
When manually adopting the composition's repeats for a source with no repeats,
the review explains that the shared composition controls execution.
The review compares source/target positions, labels, meters, durations, pickup
status and tempo declarations before confirmation. A bounded table previews the
first 128 measures; the same alignment validator checks the complete source.

Project manifest schema 2 stores rehearsal settings and each appended part's source
filename, checksum, source ID and anchors beside canonical MusicXML. Schema 1 loads
with default settings and is upgraded on the next durable save. Playback device
availability and the running clock are transient. Preferences participate in
autosave without modifying musical notation. Existing checksum, staging, backup
and recovery mechanisms remain authoritative. Invalid rehearsal settings are
validated before replacing the current workspace.
Optional additions to schema 2 default safely when absent in older manifests.
Twenty-four named A–B study ranges and progressive-tempo settings are retained.
The MIDI sequence marks the beginning of every loop; the initial entry is ignored,
and subsequent entries count completed iterations independently of HTTP polling.
Tempo grows by 1–20 BPM every 1–20 loops, up to a user-selected target no greater
than 300 BPM. Stopping resets the iteration counter; disabling A–B disables the
progression. Running position and completed-iteration count are not persisted.

## Bounds

The existing 480 PPQ exact timing and bounded non-nested repeat model remain.
Playback supports at most 15 melodic parts. Unpitched percussion,
nested tuplets, D.C./D.S./coda and arbitrary re-barring are unsupported and must not
be advertised as playable. Manual anchors operate at written measure boundaries;
they cannot repair a different pickup length or infer missing musical material.
The combined score must remain within the importer limits so saved projects can
be reopened. Instrument recognition does not promise an installed sample or
physical-device sound quality.
