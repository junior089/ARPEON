# Milestone 9 — local ML evaluation

The measured problem is recovery of the score position after missed notes, wrong pitches,
repeats and distant jumps. The production baseline is `ScoreFollower`, the deterministic
Milestone 8 matcher. `arpeon-ml` contains an **experimental** position model trained from
labeled synthetic transitions. It is not connected to microphone capture or the UI.

Run the reproducible evaluation offline with:

```sh
./gradlew :modules:arpeon-ml:test :modules:arpeon-ml:run
```

The generator uses Java `Random` with seeds 1–400 for training and disjoint seeds
10001–10100 for evaluation. Each of the 100 held-out traces contains 28 observed pitches over
an original 32-note synthetic score. It simulates small skips, repeats, distant jumps and
one-semitone pitch errors. The labels are the generated score indices. No user audio, imported
music, outside recording or third-party dataset is used.

| Held-out measure | Deterministic baseline | Experimental model |
| --- | ---: | ---: |
| Exact position, 2800 observations | 56.50% | 85.57% |
| Mean observations until recovery after 218 distant jumps; unresolved by five observations counts as six | 4.24 | 1.27 |
| False jumps on a non-jump observation | 91 | 184 |
| Example local p95 compute time per observation | 0.0018 ms | 0.0292 ms |

The position and jump counts are reproducible from the seeds. Latency is machine and JVM
dependent; the command reports the current measurement. The model improves synthetic position
accuracy and recovery, but **doubles false jumps**. The synthetic generator also shares its
error distribution with training. There is no rights-cleared real-performance benchmark yet.
The model is **rejected for product use**. Promotion requires a measured gain on an independent
real-performance set, no regression in false jumps, acceptable latency and memory on supported
desktop hardware, and documented dataset rights. Existing deterministic behavior remains in
the app.

## Experimental model record

| Field | Value |
| --- | --- |
| Name/version | Synthetic transition follower, evaluation version 1 |
| Source/license | Original ARPEON Java source, AGPL-3.0-or-later |
| Training dataset/rights | Procedurally generated scores and labeled traces, seeds 1–400; original project material under AGPL-3.0-or-later |
| Benchmark dataset | Held-out generated traces, seeds 10001–10100; no overlap with training |
| Input/output | Score MIDI pitch sequence and one observed MIDI pitch → estimated score-note index |
| Sample rate/window | Not applicable; this experiment consumes note events, not audio windows |
| Latency/CPU | See live command output; one CPU, no accelerator or network |
| RAM | Small parameter arrays and one score-length state vector; no persistent model file |
| Accuracy metric | Exact position percentage, recovery observations, false-jump count |
| Known limitations | Synthetic-only distribution; repeated motifs and acoustic capture errors are underrepresented; no calibrated confidence or real-device validation |
| Parameter checksum | SHA-256 `653bc5cdfbba9dcd3b3ee3b9eacf51272d3c78c4fd1efd5bf83ecc1a46206cda` |

The model learns four transition and three pitch-emission probabilities from labeled traces
with Laplace smoothing and uses online Viterbi updates. Parameters are regenerated in memory
by the benchmark. There is no bundled large model, model download, remote inference or audio
upload.
