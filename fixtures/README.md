# Fixture provenance

`musicxml/first-notes.musicxml` is an original two-measure test melody constructed for this
project, licensed AGPL-3.0-or-later. It is not transcribed from a published score. It contains
C4, D-sharp4, a half rest and a whole A4, with explicit 4/4, divisions and tempo.

`musicxml/two-parts.musicxml` is an original two-part, two-measure melody/accompaniment
written for Milestone 1 tests, also AGPL-3.0-or-later. It exercises part alignment, 4/4 to
3/4 and 90 to 120 BPM changes. It is not transcribed from a published score.

`musicxml/badinerie-for-flute-by-js-bach.mxl` and `.mscz` are user-provided regression samples.
Their embedded metadata declares Creative Commons Zero 1.0 / Public Domain; the MusicXML source
credits J. S. Bach and engraver James Brigham and identifies the source score as
https://musescore.com/user/58480/scores/698281. They cover the public MusicXML DOCTYPE and
MuseScore compressed-project conversion. Grace-note ornaments and engraving details are outside
the current score model.

Audio fixtures are generated in test code (sines, harmonics, DC, silence and seeded noise).
No sound recordings are checked in. Hostile XML cases are short original strings in tests;
they use nonexistent resource targets and do not contain private data.
