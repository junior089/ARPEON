# Timing and units

Musical duration is a reduced positive rational in **whole notes**. Tempo explicitly
means quarter notes per minute. The domain does not interpret musical position as seconds.
Milestone 1 converts exact durations to 480 quarter-note ticks. One Java MIDI sequencer
drives playback, metronome, count-in and score cursor position. Tempo events are at measure
starts; the score timeline converts tick ↔ seconds piecewise across tempo changes. BPM
control scales the imported tempo map. Pickup durations use their actual event length.
The UI polls the sequencer position for display; the browser timer is not musical authority.

An audio frame records sample rate (Hz), sequence and an estimated wall timestamp of
its first sample. The capture adapter subtracts window duration and observed queued input
from the read-completion timestamp. This is not a hardware clock calibration.

Displayed window latency is `samples * 1000 / sampleRate`; processing duration is measured
with `System.nanoTime`. Analysis age is the estimated time from the first sample to analysis
completion. Unknown hardware latency and subsequent HTTP/UI delay are not included.
Do not add these overlapping quantities to invent a total-latency measurement.

Tuner UI polling runs after each HTTP response at roughly 100 ms intervals; score transport
polling refreshes the cursor at roughly 80 ms intervals. Neither is an audio or musical
timing clock. Hardware validation must measure end-to-end behavior before latency claims.
