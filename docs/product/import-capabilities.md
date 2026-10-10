# Import capabilities at Milestone 4

All import is local. The original bytes are hashed before conversion and are not written by the
desktop app. The review preview stays separate from the open score. Opening a draft requires an
explicit acknowledgement; exported MusicXML contains the resulting notes, not the source bytes,
warnings or review history. Keep the original file if source traceability is needed after closing.

## MusicXML

Uncompressed UTF-8 `score-partwise` in `.musicxml`/`.xml`, compressed `.mxl`, or MuseScore `.mscz`, up to 128 MiB
of input locally. MXL must include `META-INF/container.xml`; the first declared rootfile is
read as the score. At most 256 ZIP entries and 128 MiB expanded content are accepted, and
archive paths are validated without extracting files to disk. Only the score is imported;
embedded media and alternate rootfiles are ignored. The editor retains the original filename
and SHA-256, keeps revisions in memory, and exports the supported subset as uncompressed MusicXML.
Inside MXL, the standard MusicXML 4.0 public Partwise DTD declaration is stripped without
resolving it; internal subsets, entity declarations and nonstandard DTDs remain rejected. MSCZ conversion
supports one pitched staff and one voice; layout, slurs and grace-note ornaments are omitted.
Scores are limited to 16 parts, 2048 measures per part and 16384 total note/rest events.
Supported: part declarations and names, a work or movement title (conflicting titles are rejected),
one or more sequential voices of notes, rests and simultaneous chord heads,
integral accidentals from double-flat through double-sharp, explicit divisions and meter,
inherited divisions/meter, a starting `sound tempo` per measure, explicitly marked incomplete
measures, sound/visual duration ties across same-pitch adjacent notes and measures, and one initial
major/minor key signature per part when all parts agree. Chord heads share the preceding
event's onset and duration. `<backup/>` and `<forward/>` preserve voice cursor movement and
relative onsets. Multiple staves remain unsupported. When a note
type or dots are present, they must agree with the explicit duration. Missing tempo remains
absent in the source; playback visibly defaults to 120 BPM. Errors in measure content identify
the part, measure, voice and note position where available. Repeats,
arbitrary directions, lyrics, clefs and unsupported semantic elements fail closed.

## Text and pasted melodic cipher

`NOTES` accepts `C D E F G A B`; `SOLFEGE` and `CIFRA_MELODICA` accept `DO RE MI FA SOL LA SI`.
ASCII `b`, `bb`, `#`, `##` and an optional octave `-1` or `0`–`9` follow each note, for example
`DO4 RE#4 MI4` or `C4 D#4 E4`. Case does **not** imply octave. Spaces, commas, semicolons and
isolated hyphens separate tokens; they do **not** imply duration, rests or measure boundaries.
The maximum is 2 MiB UTF-8 and 8192 notes. Bar marks, words, chords, repetitions and mixed or
unknown notation are rejected with a token-level reason. Cifra Melódica paste uses the same
strict solfège grammar; it does not scrape or interpret a provider's entire page. The draft
remains a pitch sequence until the user chooses an octave for missing values and an equal note
duration. That duration is recorded as an assumption, never attributed to the source.

## MIDI

Local `.mid`/`.midi` up to 32 MiB. PPQ timing is kept only when every note boundary maps exactly to
the editor's 480 ticks per quarter. Up to 128 tracks, 131072 events per track, 8192 notes and one
non-percussion channel are accepted. Notes must form one monophonic voice with paired on/off
events. Polyphony, multiple channels, overlapping same-pitch notes, SMPTE timing, unsupported
tick resolution and notes crossing a 4/4 barline are rejected. Original MIDI onset and duration
ticks are preserved in the draft; silence becomes rests. Enharmonic spelling is a default
representation, not recovered notation intent. Pedal, tempo changes, expression, articulation,
voice separation and layout are not imported. Playback uses the visible 120 BPM default.

## Monophonic audio prototype

`.wav` mono signed little-endian PCM, 16-bit at 16 or 48 kHz, at most 120 seconds and 32 MiB.
YIN estimates stable pitches from overlapping analysis windows. The draft reports confidence,
estimated onset and length; it is not exact transcription. The review currently converts the
accepted pitches to equal durations chosen by the user in 4/4, rather than silently treating
estimated timing as rhythm. Silence and unstable frames may be omitted. Polyphony, noisy audio,
other codecs and longer recordings are outside this prototype. Processing a long file can take
substantially longer than playback time on a slow CPU; no audio leaves the machine.

ABC import is deferred: the optional Milestone 4 clause requires a justified subset, while
supporting it honestly would require handling keys, meter, rests, tuplets and repeats that
the current score model cannot retain. Unsupported ABC is rejected rather than misread as text.
Milestone 5 adds one approved Wikimedia Commons MIDI file-page URL path. MuseScore automated
access and Cifra Melódica site access remain disabled; their manual export/paste flows above
remain available. See the [provider access review](../legal/provider-registry.md).

## OMR (Milestone 6)

Audiveris must be installed separately and its executable path supplied through
`ARPEON_AUDIVERIS_EXECUTABLE`. One local PDF (1–32 pages, at most 64 MiB) or PNG/JPEG (at most
32 MiB, at most 64 megapixels) is accepted. Recognition has a ten-minute deadline and must
produce exactly one uncompressed MusicXML score of at most 128 MiB. The current score importer
supports its documented voice-aware subset, including simultaneous chord heads and independent
sequential voices. Ties are preserved and sounded as sustained notes; chord playback schedules
all heads together. Non-nested repeats (2–8 passes) and contiguous pass-specific endings are
preserved and expanded only during playback. Nested repeats/endings, discontinuous endings,
tuplets and unsupported musical structures fail visibly rather than being flattened. Live
analysis still requires non-overlapping monophonic notes.
Presentation metadata and slurs may be omitted with a warning. Confidence by note is unavailable;
the preview lists recognized notes and rests by measure (up to 16 parts, 2048 measures and
16384 events), and every page and measure requires comparison with the original. The preview holds no durable
Audiveris project or source image, and exported MusicXML contains the edited notation, not
the original scan or OMR review history.

The local caps are intentionally much larger than the original prototype limits. MusicXML UTF-8
validation and browser upload are chunked and cancelable, but the server still holds the request
bytes and builds in-memory document trees and large previews. A file near the cap needs substantial
RAM. The Wikimedia adapter keeps its separate
1 MiB network download cap. Large local imports do not imply broader automated web access.
