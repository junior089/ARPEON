# ADR 0008 — Multi-instrument rehearsal

Status: accepted.

## Decision

Extend the existing immutable `Score` / `Part` document, exact `ScoreTimeline`,
execution map and single Java Sound sequencer. Part IDs survive renaming,
reordering, replacement and persistence. Imported parts receive new IDs to avoid
collisions between separately exported files. Composer, per-measure clefs and
simple tuplet ratios and boundaries remain musical data through edits and export.

Part selection controls notation independently of mute, solo and volume. Full,
individual and focus views filter only the renderer's input. The canonical
MusicXML continues to contain every part. The existing explorer owns management;
the collapsible mixer sits immediately above the existing transport.

The receiver applies individual gain multiplied by master through MIDI expression,
including to sustained notes. It reapplies gain and pan after sequencer controller
chasing/resets. Mute wins over solo; multiple solos form a union; clearing the last
solo restores the independently retained mute states. Presets are explicit,
one-time operations and do not bind future visual selection to the mixer.

Sounds use the existing local Java Sound synthesizer and its installed/default
General MIDI bank. There is no new package, network sound download or proprietary
sound asset. Actual per-part loading results are exposed separately from recognized
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

Project manifest schema 2 stores rehearsal settings and each appended part's source
filename, checksum, source ID and anchors beside canonical MusicXML. Schema 1 loads
with default settings and is upgraded on the next durable save. Playback device
availability and the running clock are transient. Preferences participate in
autosave without modifying musical notation. Existing checksum, staging, backup
and recovery mechanisms remain authoritative. Invalid rehearsal settings are
validated before replacing the current workspace.

## Bounds

The existing 480 PPQ exact timing and bounded non-nested repeat model remain.
Playback supports at most 15 melodic parts. Independent staves, unpitched percussion,
nested tuplets, D.C./D.S./coda and arbitrary re-barring are unsupported and must not
be advertised as playable. Manual anchors operate at written measure boundaries;
they cannot repair a different pickup length or infer missing musical material.
The combined score must remain within the importer limits so saved projects can
be reopened. Instrument recognition does not promise an installed sample or
physical-device sound quality.
