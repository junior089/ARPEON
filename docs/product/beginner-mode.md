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
holds at most 50 summaries and no audio samples. Deleting that local file clears the history.
Closing without saving an attempt leaves no summary. Hardware microphone, timing latency and
audible flute quality still require device testing.
