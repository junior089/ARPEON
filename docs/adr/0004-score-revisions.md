# ADR 0004 — Editable score revisions and instrument profiles

Milestone 2 keeps the imported `Score` immutable and records each accepted edit as a new
document state containing the score, chosen instrument profiles and key. Undo/redo restores
that state; it never reloads or writes over the user's source. Before a state is committed,
MusicXML export, the score timeline and MIDI sequence are validated together. Editing stops
the previous transport instance and rebuilds it from the accepted state.

Note insertion consumes time from a selected rest. Deleting a note leaves an equal-duration
rest. Lengthening an event consumes adjacent rests; shortening creates a rest. These rules
preserve measure totals and make unsupported rhythmic changes fail visibly. Interval, key,
octave and instrument transformations generate a revision-bound preview token, changed-note
list and range warnings before application. Source and target keys are chosen explicitly; no
key-detection guess changes the score.

The first concrete voice is the transverse flute (General MIDI program 74, encoded as
zero-based MIDI program change 73). Its baseline written/sounding range is C4–C7; C4–C6 is
an initial practice warning threshold, not a universal physical limit. B-flat clarinet
demonstrates written-to-sounding conversion and MusicXML `<transpose>`. The generic profile
keeps other parts playable without asserting an instrument-specific range. No SoundFont or
sample pack is bundled; audible quality and device availability need real-hardware checks.

Exports use the supported monophonic MusicXML subset, with initial key, instrument and MIDI
program metadata when known. Unknown notation and unsupported instrument changes still fail
closed. Score projects are not persisted yet; users must export their edits before closing.

References: [Yamaha on concert-flute range](https://hub.yamaha.com/winds/wood/the-woodwind-family-explained/),
[MIDI Association General MIDI](https://midi.org/general-midi), and
[W3C MusicXML instrument/transposition guidance](https://www.w3.org/2021/06/musicxml40/tutorial/midi-compatible-part/).
