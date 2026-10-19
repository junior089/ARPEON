# MusicXML boundary

The score importer accepts XML streams and bounded `.mxl`/`.mscz` archives. It does not fetch URLs
or mutate source files. Input is capped at 128 MiB before parsing; archives additionally limit
the entry count and expanded content and read the
first manifest rootfile without extracting archive paths to disk. External DTD/entity resolution,
XInclude and external schema access are disabled. In MXL, only the standard MusicXML 4.0 public
Partwise DOCTYPE is stripped before parsing; internal entities remain rejected. MSCZ files are converted
from a bounded, securely parsed single-staff MuseScore document.
XML depth is capped at 32 and attributes per element at 16.

It returns a score plus source filename, SHA-256 of the original bytes and parser version.
No rights are inferred from the file. Caller owns the stream; parser resources are local.
In Milestone 1, the browser sends the selected file's original bytes to the authenticated
loopback API. The parser validates it before the UI hands its UTF-8 text to the local SVG
renderer. In the initial milestones, the score and provenance stayed in memory. The current
desktop application can persist a named project with canonical MusicXML and that provenance;
the importer itself remains independent of the project store.

New direct MusicXML/XML/MXL/MSCZ imports now preserve exact original bytes in the
profile's project, separately from canonical revisions. The schema-3 evidence and
verified private download are defined in [ADR 0011](../adr/0011-initial-source-evidence.md).
Legacy projects and draft/OMR/part-import flows do not gain reconstructed originals.

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

Milestone 5 adds a single-URL path for approved Wikimedia Commons MIDI file pages. A static
provider registry rejects unknown and manual-only hosts before network access. The adapter uses
the official imageinfo API and approved media host with no redirects, bounded bytes and time,
rate control, provider throttling, MIME/header/size/checksum validation and no cache. The source
page, retrieval time, declared rights and transformation history accompany the preview and
accepted in-memory score. Local bytes remain local. See the [access review](../legal/provider-registry.md).

Milestone 6 adds `arpeon-omr`. The authenticated bridge receives one local PDF/PNG/JPEG and
validates its name, signature, size, page count or image dimensions before launching the
configured Audiveris executable. The CLI runs in batch with a ten-minute deadline and a
temporary output directory, exporting uncompressed MusicXML. The adapter accepts exactly one
bounded XML score, removes presentation-only markup and rejects musical structures the narrow
MusicXML importer cannot represent (multiple staves and unsupported notation). Supported bounded
repeats remain intact. An OMR-only preparation step consolidates identical repeated keys,
preserves exact tuplet durations without their visual grouping, leaves explicit trailing space
in incomplete measures and records provisional meters or empty-measure rests in review warnings.
Ordinary MusicXML imports stay strict; actual key changes and unrepresentable music fail visibly.
Independent voices, cursor movement and single-voice chords are preserved. Its result remains pending until the user reviews the
original and acknowledges the OMR preview; only then is the score opened. Original SHA-256,
source filename, measure-by-measure event labels and recognition warnings remain in memory.
No Audiveris type enters the domain.

`X-Arpeon-Job` identifies each preview request. Authenticated `POST /api/omr/cancel` with
`{jobId}` interrupts only the corresponding native process tree or invalidates its pending preview.
A bounded tombstone set prevents a cancelled upload from subsequently starting recognition.
`GET /api/omr/status` exposes reading, recognizing, preparing and cancelling phases. Cancelled jobs
never publish a late result. The HTTP boundary clears the worker's native interruption before
writing the terminal response; native process code itself preserves interruption.
The original PDF/image is retained as a MIME-controlled local browser blob for comparison.
For PDF pages, authenticated `POST /api/omr/pdf-page` accepts the original bytes and filename
plus a one-based `X-Arpeon-Page`. PDFBox validates the same input limits and generates a PNG
with its longest edge capped at 1600 pixels; one request can render at a time. The browser
shows loading, retry and page navigation and revokes images after replacement or dismissal.
No native PDF plug-in, persistent page cache or remote service is required.
