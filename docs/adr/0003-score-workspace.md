# 0003 — Score workspace and canonical transport

Status: accepted for Milestone 1.

The validated, uncompressed MusicXML subset is uploaded over the existing authenticated
loopback bridge. Java retains an immutable score and its provenance in memory; the original
file is never modified. The browser keeps the selected XML for rendering. An unsupported
or inconsistent score fails before replacing the previous workspace.

`arpeon-transport` maps exact rational durations into 480 quarter-note ticks and explicitly
converts between score position and seconds across measure-start tempo changes. Parts must
share measure timing. A source without tempo gets a 120 BPM *playback* default; this is not
an inferred source marking. Repeats, mid-measure tempo changes and tuplets remain unsupported.

`arpeon-playback` converts those ticks to a Java Sound MIDI sequence. The sequencer is the
single running clock for notes, score position, metronome, count-in and seek. Browser polling
only refreshes the display. Track mute/solo, a user-set starting BPM and generic MIDI timbre
are local controls. The synthesizer opens on Play, never on import. A five-second poll lease
stops unattended playback. No external soundfont is bundled.

OpenSheetMusicDisplay 2.2.0 renders validated MusicXML as SVG in a lazy frontend chunk. It
is BSD-3-Clause and uses permissively licensed VexFlow, JSZip (MIT option), pako (MIT and
Zlib), loglevel and typescript-collections. Notices are retained in the repository and
desktop distribution. Its CommonJS bundle is an isolated build tradeoff; the Angular
initial bundle stays small. A future renderer change needs a separate decision.

This milestone does not add score editing, persistence, OMR, ML or web import.
