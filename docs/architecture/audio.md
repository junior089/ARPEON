# Audio

Java Sound enumerates mixers supporting 16-bit signed little-endian **mono** PCM at
48 kHz or 44.1 kHz. Start tries supported rates in that order. Selection is session-local;
device handles are not persisted. Refresh replaces the handles, so stale selections fail
instead of selecting another mixer by its array index. Refresh after hardware changes. Devices that expose
only other formats are not offered. OS permission failures are actionable UI errors.

Capture reads 4096 samples on an owned thread, then offers a frame to a two-slot queue.
When full, it drops the oldest waiting frame and increments a counter. No DSP, network,
disk or UI work runs in capture. A separate owned worker runs YIN. Stop closes the line
to unblock reads, interrupts analysis and waits for termination. No samples are persisted.

YIN uses a half-frame difference window, cumulative mean normalized difference, a 0.15
first-trough threshold and parabolic interpolation. Supported frequency range: 50–2000 Hz.
The AC RMS gate is 0.005 (DC removed for gate measurement). Values below it are silence;
no qualifying trough is low confidence. Three successive detections within 20 cents
are needed before displaying a note. This is a stability guard, not musical grading.

Confidence is `clamp(1 - normalizedDifferenceAtTrough, 0, 1)`, not a calibrated probability.
Synthetic tests cover sine, detuning, harmonic-rich signals, DC, silence and seeded noise.
A harmonic-rich synthetic signal is not a validated real flute recording.

The input-overrun counter reports observed near-full buffers; the Java Sound API does
not expose an exact dropped hardware sample count. A stalled read is diagnosed after
two seconds. There is no output stream, so output underruns do not apply. Device disconnect
and permission behavior still require the manual hardware matrix in the testing guide.
