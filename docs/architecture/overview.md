# Architecture

```text
Java Sound → AudioFrame → bounded queue → Java YIN → TunerObservation
                                                        ↓
                                  authenticated loopback HTTP → Angular

Uncompressed MusicXML → secure parser → immutable imported score
                                            ↓
                              editable score revisions → MusicXML export
                                            ↓
                                  exact score timeline → Java Sound MIDI
                                            ↓                    ↓
                            authenticated loopback → Angular SVG score/editor

Paste / MIDI / WAV → bounded local parser → source-aware import draft
                                               ↓ explicit review
                                        editable score revisions

Selected score part → timed expected events ──────────────┐
Java Sound → tuner pitch windows → onset segments ────────┼→ end-of-attempt alignment
                                                         └→ separate analysis metrics
```

All processing is local. Domain code has no infrastructure dependencies. Tuner works
without a score. The active score workspace is in memory; named projects persist canonical
MusicXML and source provenance under the user's ARPEON data directory. The desktop serves
compiled static assets, composes capture/analysis and owns the transport. Performance analysis
snapshots one score revision and reports after an attempt; it does not track a moving score
position.
Projects belong to local study profiles. New direct score imports also retain
verified original bytes separately from editable MusicXML, with initial-source
identities and unassessed rights; see [ADR 0011](../adr/0011-initial-source-evidence.md).
See [boundaries](module-boundaries.md), [bridge ADR](../adr/0002-local-desktop-bridge.md)
and [score revision ADR](../adr/0004-score-revisions.md),
[local project ADR](../adr/0005-local-score-projects.md).
