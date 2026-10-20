# Module boundaries

| Module | Owns | Project dependencies |
| --- | --- | --- |
| arpeon-domain | Musical values, immutable score revisions, instrument profiles and pitch transformations | None |
| arpeon-musicxml | Secure, limited XML import/export and provenance | domain |
| arpeon-audio | Device discovery, PCM capture, immutable frames, lifecycle | None |
| arpeon-dsp | Deterministic YIN and detection confidence | audio |
| arpeon-tuner | Reference, pitch mapping, stability, capture session | domain, dsp/audio |
| arpeon-transport | Exact score timeline, tempo and position mapping | domain |
| arpeon-playback | MIDI sequence, synthesized output and transport controls | transport |
| arpeon-import | Bounded local text, MIDI and WAV draft parsers; explicit score conversion | domain, audio, dsp |
| arpeon-omr | PDF/image preflight, Audiveris process boundary and conservative MusicXML normalization | MusicXML, PDFBox |
| arpeon-analysis | Bounded onset segmentation, fixed-tempo expected/observed alignment and separate metrics | None |
| arpeon-ml | Offline experimental score-position model and reproducible synthetic benchmark; no runtime integration | analysis |
| apps/desktop | Composition, local bridge, Commons API adapter and provider policy, score project persistence, active score/import/OMR/analysis workspaces, practice exercises and summaries, resource serving | tuner, MusicXML, import, OMR, playback, analysis |
| frontend | Score SVG, import review, editing, practice and analysis controls, transport controls, tuner display | HTTP contract |

The importer is exposed through the authenticated local bridge. No empty future modules exist. Interfaces
represent external capture and interchangeable detection; score events use a sealed
musical union. Written pitch is explicit. Instrument transposition is a profile value,
not a flute-specific branch.
The flute profile identifies its documented fingering reference; other profiles have no
fingering dataset and the practice UI does not infer one.

`ScoreSourceProvider` is the implemented URL-preview boundary; `SourceRegistry`
gates access and exposes actual capabilities before network calls. The desktop
project store owns append-only local edition declarations linked to immutable
initial source evidence ([ADR 0012](../adr/0012-source-capabilities-local-editions.md)).
These declarations do not verify a catalogue or grant publication rights.
