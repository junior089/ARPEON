# ADR 0007 — Polyphony, voices and written/execution order

Status: accepted, phased implementation. Chords, independent sequential voices and bounded,
non-nested repeat execution with pass-specific endings flow through MusicXML import/export
and playback. Editing and score-event access retain written-measure identity.

## Context

The current `Measure.events` sequence implies one monophonic voice. A MusicXML `<chord/>`
shares an onset with the preceding note, while `<backup/>` and `<forward/>` position
independent voices on a measure-wide clock. Repeat barlines and endings change playback
order without changing the written measure sequence. Flattening either structure into
the existing list can silently alter onset, duration, cursor location or export.

## Target model and current boundary

- Keep written notation canonical. A measure owns one or more identified voices; each
  voice contains sequential timed events. A timed event has an explicit rational offset
  and duration, so voices can be compared without inferring order from list position.
- Represent a chord as one onset/duration with multiple note heads. Each head retains
  written pitch, accidental spelling, tie endpoints and a stable head identity. Rests
  remain voice events and cannot contain heads.
- Build playback as a separate execution map over written measure references. Each
  occurrence records a written measure index and pass/ending context. Repeat expansion
  is bounded, deterministic and visible to the transport; notation and MusicXML export
  retain the written order and repeat marks.
- Address an editable note by part, measure, voice, event and chord-head identity. A
  whole-chord operation must be explicit; single-head editing cannot change sibling
  pitches or durations.
- Keep live pitch-following and performance scoring monophonic until a separately
  validated voice-selection/alignment model exists. Polyphonic playback must not imply
  polyphonic analysis support.

The current domain uses `Voice` and `TimedEvent` with exact rational offsets. A `Chord` is one
event with multiple equal-duration note heads. Head-specific pitch, deletion and tie edits
preserve sibling heads; duration edits are confined to the selected voice; score-wide pitch
transforms retain every voice and offset. The timeline places events from different voices at
their written onsets and counts each event duration once. MusicXML `<backup/>` and `<forward/>`
move the measure cursor between voices. Live analysis explicitly rejects a selected part
containing overlapping pitches. Independent staves remain outside the model. Repeat marks
are separate from written measures; the transport expands
at most eight passes and skips contiguous ending ranges that do not match the current pass.

## Import/export contract

MusicXML import preserves chord heads marked with `<chord/>`, groups events by voice ID and
uses `<backup/>`/`<forward/>` to retain exact onsets. It imports and exports non-nested forward/
backward repeat markers with 2–8 passes and contiguous pass-specific endings. Nested repeats,
nested endings, malformed markers, unequal chord durations, a head without a preceding note
in the same voice and unsupported staves fail closed. Export retains written order and marks;
the execution map expands only for playback. Round-trip tests compare musical structure, not
XML text.

## Migration and release sequence

1. **Implemented:** introduce the chord event and validation while retaining sequential
   note/rest events; update timeline, MIDI playback, selection, per-head editing, undo/redo,
   transforms, analysis guards, UI and MusicXML import/export/reimport.
2. **Implemented:** model independent voices with explicit rational offsets; preserve them
   through import, export, timeline, MIDI, OMR review, score event paging and voice-specific
   pitch/duration/tie/delete edits. Explicit IDs remain the edit identity.
3. **Implemented for the bounded subset:** `RepeatRegion` and `EndingRegion` retain written
   notation; `ExecutionMap` creates bounded playback occurrences with pass context; timeline,
   MIDI and metronome follow the expanded order; MusicXML and OMR normalization preserve repeat
   marks. Nested repeats/endings and more than eight passes remain rejected.
4. Add licensed real-score fixtures and full round-trip tests for each newly supported
   subset. Keep unknown MusicXML structure as a located error.

## Consequences

- The domain distinguishes written order from performed order and can represent shared
  onsets without duplicating duration semantics.
- Existing single-voice workflows retain their sequential event order; chord duration is
  counted once and MIDI heads share a tick. Multi-voice analysis remains explicitly rejected
  when note intervals overlap.
- Chord-aware fingering, instrumental range checks, polyphonic transcription and live
  voice following remain separate product capabilities and need their own acceptance
  data.

## Alternatives considered

- Flatten chord heads into consecutive notes: rejected because it serializes simultaneous
  notes and shifts every following onset.
- Store repeat expansion directly in `Part.measures`: rejected because it loses the
  distinction between written notation and playback occurrences.
- Treat each MusicXML voice as an unrelated part: rejected because it changes instrument,
  transposition and part-level provenance semantics.
