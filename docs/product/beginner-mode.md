# Beginner practice

The Practice area generates note, long-tone, major/minor/chromatic-scale, arpeggio and basic
rhythm exercises from the same `Score` domain as imported music. Exercises are deterministic
for their selected key, octave and type. Out-of-range flute exercises fail before display.
MIDI reference playback uses the flute profile, metronome and count-in; the loop option
repeats playback and can increase BPM by a bounded step. It does not claim to assess the
player's acoustic quality.

The note trainer reports detected pitch, cents relative to the target, confidence and
response time. The long-tone trainer reports validated sustained milliseconds, root-mean-square
cents and detected interruptions. Rhythm taps report signed timing error against server time;
network latency is not calibrated. Feedback is measurement-based, not a single grade.

The offline flute fingering image is [CC0](../../THIRD_PARTY_NOTICES.md), with Yamaha's
[official guide](https://www.yamaha.com/en/musical_instrument_guide/flute/play/play002.html)
as a check. Alternative fingerings and trills are not catalogued, so the interface does not
guess them. The chart is only for the concert flute profile.

Press **Encerrar e salvar** to store a summary in `~/.arpeon/practice-history.json`. History
holds at most 5000 summaries and no audio samples. Deleting that local file clears the history.
History shows the newest ended attempts first, 20 per page, with exercise, completed/interrupted
and local calendar date filters. Both selected days are included. Counts describe all matching
summaries, not only the displayed page; they do not assess acoustic quality or prove improvement.
Clearing filters retains the stored history. A read error offers retry without blocking practice.

The authenticated local `GET /api/practice/history` returns an object with `entries`, zero-based
`page`, `pageSize`, `matchingCount`, `totalCount` and `completedCount`. Page size defaults to 20
and is limited to 1–50. Optional `kind` and `outcome` accept the exercise identifiers and
`completed` / `interrupted`; `from` and `until` use ISO instants, inclusive/exclusive respectively.
The UI converts the final selected local day to the following midnight. Out-of-range pages
clamp to the last available page. Unknown/duplicate parameters and invalid values return 400.
`POST /api/practice/finish` returns only `summary`; the UI refreshes the first history page with
the current filters. File retention and atomic writing are unchanged. The server still reads
the bounded JSON file (8 MiB / 5000 entries); pagination bounds transfer and DOM size, not disk I/O.

Closing without saving an attempt leaves no summary. Hardware microphone, timing latency and
audible flute quality still require device testing.

Audio preparation shares input choice and A4 measurement reference across Practice, Tuner and
Analysis. Choices survive restarts in `~/.arpeon/audio-preferences.json`; permission to capture
does not. Reconnect and refresh a missing saved input, or choose a replacement. Valid A4 values
range from 400 to 480 Hz. **Ouvir Lá 4** plays a two-second tone through the browser output and
can be stopped; it stops on navigation and blocks capture while playing. This volume test does
not calibrate hardware latency or change the system MIDI synthesizer's tuning. Write failures
remain visible with retry; valid current settings can still be used in the open window.

Choose exercise, key and tempo, then **Gerar exercício**. Scale exercises also show scale type.
**Ajustes avançados do exercício** contains octave, repeat playback and its tempo increment;
the increment only affects repeating reference playback, not the attempt's evaluation tempo.
In Analysis, choose mode and part, prepare the shared audio, then start. Advanced criteria
are initially folded; **Restaurar ajustes da análise** restores 450 ms alignment, 120 ms entry,
50 cents pitch, 20% rhythm/duration and 0 ms latency compensation while retaining part and audio.
Zero compensation does not mean calibrated latency. These tolerances are comparison criteria,
not skill levels. Invalid or empty fields block the initial action and explain correction,
even when advanced controls are folded. Choices survive navigation in the open window;
exercise settings and analysis criteria reset when the app is reopened.
