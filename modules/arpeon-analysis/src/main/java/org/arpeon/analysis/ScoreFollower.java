package org.arpeon.analysis;

import java.util.List;

/** Bounded monophonic pitch follower. Time never advances the cursor through silence. */
public final class ScoreFollower {
  public record Position(
      int noteIndex,
      String state,
      double confidence,
      int confirmedNotes,
      double tempoRatio,
      int tempoSamples,
      String tempoState) {}

  private final List<ExpectedEvent> score;
  private int position = -1;
  private int pending = -1;
  private int confirmedNotes;
  private int lastPitch = -1;
  private int stableWindows;
  private long lastWindowAt = Long.MIN_VALUE;
  private String state = "WAITING";
  private double confidence;
  private int lastTimedIndex = -1;
  private long lastConfirmedAt = Long.MIN_VALUE;
  private double tempoRatio = 1;
  private int tempoSamples;
  private boolean tempoOutlier;

  public ScoreFollower(List<ExpectedEvent> score) {
    if (score == null || score.isEmpty() || score.size() > PerformanceAnalyzer.MAX_EXPECTED_EVENTS)
      throw new IllegalArgumentException("Expected 1–2048 score notes");
    this.score = List.copyOf(score);
  }

  /** Accept one distinct tuner window; two stable windows establish a new note. */
  public Position accept(long timestampMs, Integer midi, double windowConfidence) {
    return accept(timestampMs, midi, windowConfidence, false);
  }

  /** An attack candidate restarts pitch confirmation for a repeated note. */
  public Position accept(
      long timestampMs, Integer midi, double windowConfidence, boolean attackCandidate) {
    if (timestampMs <= lastWindowAt) return position();
    if (lastWindowAt != Long.MIN_VALUE && timestampMs - lastWindowAt > 350) resetPitch();
    lastWindowAt = timestampMs;
    if (midi == null || midi < 0 || midi > 127 || windowConfidence < .65) {
      resetPitch();
      return position();
    }
    if (attackCandidate && midi == lastPitch && stableWindows >= 2) stableWindows = 1;
    if (midi != lastPitch) {
      lastPitch = midi;
      stableWindows = 1;
      return position();
    }
    if (++stableWindows != 2) return position();
    confidence = windowConfidence;
    int local = localMatch(midi);
    if (local >= 0) {
      recordTempo(timestampMs, local);
      position = local;
      pending = -1;
      confirmedNotes++;
      state = "FOLLOWING";
    } else if (pending >= 0 && recoveryContinuation(pending, midi) >= 0) {
      position = recoveryContinuation(pending, midi);
      recordTempo(timestampMs, position);
      pending = -1;
      confirmedNotes += 2;
      state = "RECOVERED";
    } else {
      pending = remoteMatch(midi);
      state = pending >= 0 ? "SEEKING" : "UNCERTAIN";
    }
    return position();
  }

  public Position position() {
    return new Position(
        position,
        state,
        confidence,
        confirmedNotes,
        tempoRatio,
        tempoSamples,
        tempoOutlier || tempoSamples < 3 ? "UNCERTAIN" : "ESTIMATING");
  }

  private void recordTempo(long timestampMs, int index) {
    if (lastTimedIndex >= 0) {
      if (index == lastTimedIndex + 1) {
        double expectedMs = score.get(index).onsetMs() - score.get(lastTimedIndex).onsetMs();
        long observedMs = timestampMs - lastConfirmedAt;
        double candidate = expectedMs > 0 ? observedMs / expectedMs : Double.NaN;
        if (expectedMs >= 100 && candidate >= .5 && candidate <= 2) {
          tempoRatio =
              tempoSamples == 0
                  ? candidate
                  : Math.exp(.75 * Math.log(tempoRatio) + .25 * Math.log(candidate));
          tempoSamples++;
          tempoOutlier = false;
        } else resetTempo(true);
      } else resetTempo(true);
    }
    if (index > lastTimedIndex) {
      lastTimedIndex = index;
      lastConfirmedAt = timestampMs;
    }
  }

  private void resetTempo(boolean outlier) {
    tempoRatio = 1;
    tempoSamples = 0;
    tempoOutlier = outlier;
  }

  private int localMatch(int midi) {
    int from = position < 0 ? 0 : position + 1;
    int to = Math.min(score.size(), from + 4);
    for (int i = from; i < to; i++) if (score.get(i).midi() == midi) return i;
    return -1;
  }

  private int recoveryContinuation(int from, int midi) {
    for (int i = from + 1; i < Math.min(score.size(), from + 3); i++)
      if (score.get(i).midi() == midi) return i;
    return -1;
  }

  private int remoteMatch(int midi) {
    int candidate = -1;
    for (int i = 0; i < score.size(); i++) {
      if (score.get(i).midi() != midi || Math.abs(i - position) <= 4) continue;
      // Repeated motifs cannot establish a unique recovery anchor.
      if (candidate >= 0) return -1;
      candidate = i;
    }
    return candidate;
  }

  private void resetPitch() {
    lastPitch = -1;
    stableWindows = 0;
  }
}
