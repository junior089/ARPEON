# Live note following (Milestone 8)

The live mode uses the selected score revision and sounding-pitch instrument profile captured
at start. It follows a single monophonic part of at most 2048 notes and ten minutes at its written
tempo. The microphone session may last up to twenty minutes so a player can pause or slow down.
Raw audio and the follow path are not saved. The existing Milestone 7 fixed-tempo analysis is a
separate mode and its metrics are not used to steer the live cursor.

Each new tuner window needs confidence at least 0.65. Two consecutive windows of the same
nearest MIDI pitch confirm a note; duplicate timestamps do not count. Silence, weak windows and
gaps over 350 ms reset this confirmation without moving the cursor. The local matcher accepts
the next four score notes, allowing a small omission. A distant note holds the current cursor
until a second distinct pitch confirms the next or following score note. Repeated motifs cannot
be used as a unique distant anchor. The UI exposes `WAITING`, `FOLLOWING`, `SEEKING`,
`UNCERTAIN` and `RECOVERED` states rather than guessing a position during ambiguity.

This is a pitch-sequence follower, not beat tracking. An experimental RMS-rise candidate with
an adaptive floor and refractory interval can split repeated notes of the same pitch. It has not
been calibrated against a labeled acoustic corpus: soft attacks can be missed, and noise or
large level changes can create false candidates. It does not align polyphony. The selected score
revision is displayed with a live cursor in the analysis area, alongside a note sequence. A missed browser
poll, weak attack, or pitch detection error can delay or misplace the cursor. Stop, navigation,
capture error, and the microphone watchdog release the device.
