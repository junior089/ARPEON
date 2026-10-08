# MusicXML boundary

The Milestone 0 importer accepts a provided stream and a basename ending in `.xml` or
`.musicxml`. It does not fetch URLs or mutate source files. Size is capped at 1 MiB before
parsing; DOCTYPE, external entities, XInclude and external schema/DTD access are disabled.
XML depth is capped at 32 and attributes per element at 16.

It returns a score plus source filename, SHA-256 of the original bytes and parser version.
No rights are inferred from the file. Caller owns the stream; parser resources are local.
In Milestone 1, the browser sends the selected file's original bytes to the authenticated
loopback API. The parser validates it before the UI hands its UTF-8 text to the local SVG
renderer. The score and provenance stay in memory; no project file is written.

The explicit subset is documented in [import capabilities](../product/import-capabilities.md).
Unsupported music fails with a checked import error. There is no partially successful
score that silently loses a voice, tie, transposition or repeat.

Milestone 2 keeps the imported score and source SHA separate from editable revisions. The
parser accepts an initial key signature and the app's limited flute/B-flat-clarinet instrument
metadata. Export writes the current revision using exact 480-division durations and preserves
known key, MIDI voice and written/sounding transposition. It does not read or write a project
file or fetch external sources.

Milestone 4 adds `arpeon-import`, a local conversion boundary that emits immutable
`ImportDraft` observations with source format, SHA-256, parser version, pitches, nullable timing
and octave, confidence, and warnings. The desktop API stores one pending preview per session.
Only explicit acceptance builds a 4/4 `Score` in the existing editor. Text never receives
implicit rhythm; audio timing is estimated and not used as exact notation. MIDI timing must
map exactly to the editor grid or fail. The accepted score view carries source kind and review
warnings; exported MusicXML is the resulting notation and does not embed that metadata.

`POST /api/import/preview` accepts bounded original bytes with explicit kind and filename over
the existing authenticated loopback bridge. `POST /api/import/accept` requires the current
preview token, fallback octave, equal-note duration and explicit acknowledgement. No URL fetch,
recursive crawl, local project file, or remote upload is involved.
