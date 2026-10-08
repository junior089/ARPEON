# Import capabilities at Milestone 4

All import is local. The original bytes are hashed before conversion and are not written by the
desktop app. The review preview stays separate from the open score. Opening a draft requires an
explicit acknowledgement; exported MusicXML contains the resulting notes, not the source bytes,
warnings or review history. Keep the original file if source traceability is needed after closing.

## MusicXML

Uncompressed UTF-8 `score-partwise` in `.musicxml`/`.xml`, up to 1 MiB. The editor retains the
source filename and SHA-256, keeps revisions in memory, and exports the supported subset.
Supported: part declarations and names, optional work title, sequential monophonic notes/rests,
integral accidentals from double-flat through double-sharp, explicit divisions and meter,
inherited divisions/meter, a starting `sound tempo` per measure, explicitly marked incomplete
measures, and one initial major/minor key signature per part when all parts agree. When a note
type or dots are present, they must agree with the explicit duration. Missing tempo remains
absent in the source; playback visibly defaults to 120 BPM. MXL/ZIP, polyphony, ties, repeats,
arbitrary directions, lyrics, grace notes, clefs and unsupported semantic elements fail closed.

## Text and pasted melodic cipher

`NOTES` accepts `C D E F G A B`; `SOLFEGE` and `CIFRA_MELODICA` accept `DO RE MI FA SOL LA SI`.
ASCII `b`, `bb`, `#`, `##` and an optional octave `-1` or `0`–`9` follow each note, for example
`DO4 RE#4 MI4` or `C4 D#4 E4`. Case does **not** imply octave. Spaces, commas, semicolons and
isolated hyphens separate tokens; they do **not** imply duration, rests or measure boundaries.
The maximum is 64 KiB UTF-8 and 512 notes. Bar marks, words, chords, repetitions and mixed or
unknown notation are rejected with a token-level reason. Cifra Melódica paste uses the same
strict solfège grammar; it does not scrape or interpret a provider's entire page. The draft
remains a pitch sequence until the user chooses an octave for missing values and an equal note
duration. That duration is recorded as an assumption, never attributed to the source.

## MIDI

`.mid`/`.midi` up to 1 MiB. PPQ timing is kept only when every note boundary maps exactly to
the editor's 480 ticks per quarter. Up to 32 tracks, 8192 events per track, 512 notes and one
non-percussion channel are accepted. Notes must form one monophonic voice with paired on/off
events. Polyphony, multiple channels, overlapping same-pitch notes, SMPTE timing, unsupported
tick resolution and notes crossing a 4/4 barline are rejected. Original MIDI onset and duration
ticks are preserved in the draft; silence becomes rests. Enharmonic spelling is a default
representation, not recovered notation intent. Pedal, tempo changes, expression, articulation,
voice separation and layout are not imported. Playback uses the visible 120 BPM default.

## Monophonic audio prototype

`.wav` mono signed little-endian PCM, 16-bit at 16 or 48 kHz, at most 5 seconds and 512 KiB.
YIN estimates stable pitches from overlapping analysis windows. The draft reports confidence,
estimated onset and length; it is not exact transcription. The review currently converts the
accepted pitches to equal durations chosen by the user in 4/4, rather than silently treating
estimated timing as rhythm. Silence and unstable frames may be omitted. Polyphony, noisy audio,
other codecs and longer recordings are outside this prototype.

ABC import is deferred: the optional Milestone 4 clause requires a justified subset, while
supporting it honestly would require handling keys, meter, rests, ties, tuplets and repeats that
the current score model cannot retain. Unsupported ABC is rejected rather than misread as text.
Web source adapters, including MuseScore and Cifra Melódica site access, remain Milestone 5.
