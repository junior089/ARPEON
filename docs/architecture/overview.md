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
```

All processing is local. Domain code has no infrastructure dependencies. Tuner works
without a score. The score workspace is in memory and the desktop serves compiled static
assets, composes capture/analysis and owns the transport.
See [boundaries](module-boundaries.md), [bridge ADR](../adr/0002-local-desktop-bridge.md)
and [score revision ADR](../adr/0004-score-revisions.md).
