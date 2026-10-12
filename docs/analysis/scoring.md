# Performance analysis (Milestone 7)

ARPEON analyzes one selected **monophonic part** after a bounded, fixed-tempo attempt. It does
not move a score cursor in response to playing. The current score revision, source SHA-256,
part, sounding transposition and written tempo map are snapshotted at start. The first score
beat is scheduled 2 seconds after microphone activation. Score ticks are converted through
the tempo map to milliseconds. A part with no notes, more than 2048 notes or more than ten
minutes is rejected. The input is capped at 4096 observed events.

## Onsets and alignment

The tuner supplies timestamped pitch windows. Only stable `TRACKING` windows with confidence
at least 0.65 create a pitch segment. Its onset is the first trusted window's capture timestamp
minus the scheduled first-beat timestamp and the user-set latency compensation (clamped at
zero). A change of nearest MIDI pitch, invalid/silent window or gap over 350 ms ends a segment.
The duration runs to the end of the last window. This is an **estimated onset**, not an acoustic
attack detector; repeated notes of identical pitch need a detected gap to separate. Rapid
articulations, polyphony, accompaniment and weak attacks may be merged or missed. No raw audio
is saved.

Events are aligned once at the end by a monotonic dynamic-programming sequence alignment.
Skipping an expected or observed event costs 1. A match is allowed only when the absolute
onset difference is at most the configured alignment window `W` (150–1000 ms). Its cost is
`|played onset − expected onset| / W + min(0.75, 0.25 × |played MIDI − expected MIDI|)`.
The minimum-cost path, breaking ties toward a match, determines correspondence. It cannot
recover from drift or variable tempo by following a new position; those are later work.

## Reported formulas

For each matched pair, `Δt = played onset − expected onset` in milliseconds,
`Δd = 100 × (played duration − expected duration) / expected duration` in percent, and
`Δc = 100 × (played MIDI − expected MIDI) + played cents relative to its nearest MIDI note`.
The latter includes semitone mistakes; the **intonation** statistic below excludes them.
All tolerances are configurable before a run: timing `T` (20–W ms), pitch `P` (10–100 cents),
rhythm/duration `R` (5–60%), and latency compensation (0–500 ms). Defaults in the UI are
`W=450`, `T=120`, `P=50`, `R=20`, compensation `0`; they are criteria, not proficiency grades.

| Field | Formula / interpretation |
| --- | --- |
| `pitchAccuracyPercent` | `100 × matched pairs with |Δc| ≤ P / matched pairs` |
| `timingAccuracyPercent` | `100 × matched pairs with |Δt| ≤ T / matched pairs` |
| `durationAccuracyPercent` | `100 × matched pairs with |Δd| ≤ R / matched pairs` |
| `interOnsetAccuracyPercent` | `100 × consecutive aligned pairs with 100 × |observed interval − expected interval| / expected interval ≤ R / eligible consecutive aligned pairs`; zero-length expected intervals are excluded |
| `meanAbsoluteTimingErrorMs` | `sum |Δt| / matched pairs` |
| `meanAbsoluteIntonationCents` | `sum |Δc| / matched pairs with the same expected and observed MIDI`; semitone errors remain in pitch accuracy |
| `meanMatchedConfidence` | Arithmetic mean of observed segment confidence over matched pairs; each segment confidence is the arithmetic mean of its windows |
| `missedCount`, `extraCount` | Unmatched expected and observed events, respectively |
| `earlyCount`, `lateCount` | Matched pairs with `Δt < −T` and `Δt > T`, respectively |

The four percentages use only their stated denominators. If no events were observed, status is
`INSUFFICIENT_DATA` and metrics/counts that would imply a performed attempt are null. With
observed events but no matches, matched-pair percentages/means are null. Inter-onset accuracy
is null with no eligible consecutive pair. Intonation is null with no matched same-MIDI pair.
No overall score, weighted composite, automatic progression or claim about musicianship is made.

The report interface shows correspondence coverage next to these metrics. Its study suggestions
group notes by performed measure occurrence, rank the proportion with a deviation or missing
correspondence, and show at most three passages. Musical deviations enter those suggestions
only at confidence 0.80 or higher; the analyzer metrics above remain unchanged. Confidence
below 0.80 and coverage below 70% prompt capture review. These are disclosed UI caution rules.
Saved reports now retain criteria, note locations and a canonical MusicXML fingerprint; old
reports without that metadata remain readable. Preparing study sets a stopped playback loop
with adjustable tempo. A new analysis still uses the complete part and written tempo.

Confidence is displayed separately; it is not secretly weighted into accuracy. The 0.65
minimum determines whether a window can become an event. Browser polling may miss windows;
low-confidence passages, microphone latency and hardware clock error limit precision. The
user should set compensation after measuring their own device. Audio capture stops on finish,
cancel, navigation, errors or the existing 5-second no-poll watchdog. Completed reports are
stored locally in `~/.arpeon/analysis-history.json`, linked to the score SHA-256, revision and
project ID when one exists. The history keeps up to 100 reports/16 MiB and can be reviewed or
deleted in the analysis panel. It stores report data and provenance, never captured PCM/audio.

The dynamic-programming choice follows the general score/performance alignment approach in
[Meron and Hirose (2001)](https://www.jstage.jst.go.jp/article/ast/22/3/22_3_189/_article/-char/en).
The exact cost, thresholds and formulas above are ARPEON's documented design choices.
