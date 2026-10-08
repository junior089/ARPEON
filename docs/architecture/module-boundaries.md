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
| apps/desktop | Composition, local bridge, in-memory score/import workspaces and practice exercises, local practice summaries, resource serving | tuner, MusicXML, import, playback |
| frontend | Score SVG, import review, editing and practice controls, transport controls, tuner display | HTTP contract |

The importer is exposed through the authenticated local bridge. No empty future modules exist. Interfaces
represent external capture and interchangeable detection; score events use a sealed
musical union. Written pitch is explicit. Instrument transposition is a profile value,
not a flute-specific branch.
The flute profile identifies its documented fingering reference; other profiles have no
fingering dataset and the practice UI does not infer one.
