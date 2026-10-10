package org.arpeon.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fixed-tempo, monotonic end-of-attempt alignment; never follows a live score position. */
public final class PerformanceAnalyzer {
  public static final int MAX_EXPECTED_EVENTS = 2048;
  public static final int MAX_OBSERVED_EVENTS = 4096;

  public record Match(
      int expectedIndex,
      int observedIndex,
      int expectedMidi,
      int observedMidi,
      double onsetErrorMs,
      double durationErrorPercent,
      double centsError,
      double confidence,
      boolean pitchWithinTolerance,
      boolean timingWithinTolerance,
      boolean durationWithinTolerance) {}

  public record Report(
      String status,
      int expectedCount,
      int observedCount,
      int matchedCount,
      Integer missedCount,
      Integer extraCount,
      Integer earlyCount,
      Integer lateCount,
      Double pitchAccuracyPercent,
      Double timingAccuracyPercent,
      Double durationAccuracyPercent,
      Double interOnsetAccuracyPercent,
      Double meanAbsoluteTimingErrorMs,
      Double meanAbsoluteIntonationCents,
      Double meanMatchedConfidence,
      List<Match> matches,
      List<Integer> missedIndices,
      List<Integer> extraIndices) {}

  private PerformanceAnalyzer() {}

  public static Report analyze(
      List<ExpectedEvent> expected, List<ObservedEvent> observed, AnalysisConfig config) {
    if (expected.isEmpty()
        || expected.size() > MAX_EXPECTED_EVENTS
        || observed.size() > MAX_OBSERVED_EVENTS)
      throw new IllegalArgumentException("Analysis event count outside supported range");
    requireOrdered(expected.stream().mapToDouble(ExpectedEvent::onsetMs).toArray());
    requireOrdered(observed.stream().mapToDouble(ObservedEvent::onsetMs).toArray());
    for (int index = 0; index < expected.size(); index++)
      if (expected.get(index).index() != index)
        throw new IllegalArgumentException("Expected indices must be sequential");
    for (int index = 0; index < observed.size(); index++)
      if (observed.get(index).index() != index)
        throw new IllegalArgumentException("Observed indices must be sequential");
    if (observed.isEmpty())
      return new Report(
          "INSUFFICIENT_DATA",
          expected.size(),
          0,
          0,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          List.of(),
          List.of(),
          List.of());
    int n = expected.size(), m = observed.size();
    double[][] cost = new double[n + 1][m + 1];
    byte[][] choice = new byte[n + 1][m + 1];
    for (int i = 1; i <= n; i++) {
      cost[i][0] = i;
      choice[i][0] = 2;
    }
    for (int j = 1; j <= m; j++) {
      cost[0][j] = j;
      choice[0][j] = 3;
    }
    for (int i = 1; i <= n; i++)
      for (int j = 1; j <= m; j++) {
        var e = expected.get(i - 1);
        var o = observed.get(j - 1);
        double missing = cost[i - 1][j] + 1;
        double extra = cost[i][j - 1] + 1;
        cost[i][j] = missing;
        choice[i][j] = 2;
        if (extra < cost[i][j]) {
          cost[i][j] = extra;
          choice[i][j] = 3;
        }
        double delta = Math.abs(o.onsetMs() - e.onsetMs());
        if (delta <= config.alignmentWindowMs()) {
          double pitchPenalty = Math.min(0.75, Math.abs(o.midi() - e.midi()) * 0.25);
          double matched = cost[i - 1][j - 1] + delta / config.alignmentWindowMs() + pitchPenalty;
          if (matched <= cost[i][j]) {
            cost[i][j] = matched;
            choice[i][j] = 1;
          }
        }
      }
    int i = n, j = m;
    var pairs = new ArrayList<Match>();
    var missed = new ArrayList<Integer>();
    var extras = new ArrayList<Integer>();
    while (i > 0 || j > 0) {
      if (choice[i][j] == 1) {
        var e = expected.get(--i);
        var o = observed.get(--j);
        double onsetError = o.onsetMs() - e.onsetMs();
        double durationError = 100 * (o.durationMs() - e.durationMs()) / e.durationMs();
        double centsError = 100 * (o.midi() - e.midi()) + o.cents();
        pairs.add(
            new Match(
                e.index(),
                o.index(),
                e.midi(),
                o.midi(),
                onsetError,
                durationError,
                centsError,
                o.confidence(),
                Math.abs(centsError) <= config.pitchToleranceCents(),
                Math.abs(onsetError) <= config.timingToleranceMs(),
                Math.abs(durationError) <= config.rhythmTolerancePercent()));
      } else if (choice[i][j] == 2) missed.add(expected.get(--i).index());
      else extras.add(observed.get(--j).index());
    }
    Collections.reverse(pairs);
    Collections.reverse(missed);
    Collections.reverse(extras);
    int count = pairs.size();
    Double pitchPercent =
        count == 0
            ? null
            : 100.0 * pairs.stream().filter(Match::pitchWithinTolerance).count() / count;
    Double timingPercent =
        count == 0
            ? null
            : 100.0 * pairs.stream().filter(Match::timingWithinTolerance).count() / count;
    Double durationPercent =
        count == 0
            ? null
            : 100.0 * pairs.stream().filter(Match::durationWithinTolerance).count() / count;
    int early =
        (int) pairs.stream().filter(p -> p.onsetErrorMs() < -config.timingToleranceMs()).count();
    int late =
        (int) pairs.stream().filter(p -> p.onsetErrorMs() > config.timingToleranceMs()).count();
    Double timingMean =
        count == 0
            ? null
            : pairs.stream().mapToDouble(p -> Math.abs(p.onsetErrorMs())).average().orElseThrow();
    Double confidenceMean =
        count == 0 ? null : pairs.stream().mapToDouble(Match::confidence).average().orElseThrow();
    Double intonation =
        pairs.stream()
            .filter(p -> p.expectedMidi() == p.observedMidi())
            .mapToDouble(p -> Math.abs(p.centsError()))
            .average()
            .stream()
            .boxed()
            .findFirst()
            .orElse(null);
    int rhythmPairs = 0, rhythmCorrect = 0;
    for (int k = 1; k < pairs.size(); k++) {
      Match before = pairs.get(k - 1), after = pairs.get(k);
      if (after.expectedIndex() != before.expectedIndex() + 1
          || after.observedIndex() != before.observedIndex() + 1) continue;
      double planned =
          expected.get(after.expectedIndex()).onsetMs()
              - expected.get(before.expectedIndex()).onsetMs();
      if (planned <= 0) continue;
      double played =
          observed.get(after.observedIndex()).onsetMs()
              - observed.get(before.observedIndex()).onsetMs();
      rhythmPairs++;
      if (100 * Math.abs(played - planned) / planned <= config.rhythmTolerancePercent())
        rhythmCorrect++;
    }
    Double rhythmPercent = rhythmPairs == 0 ? null : 100.0 * rhythmCorrect / rhythmPairs;
    return new Report(
        "ANALYZED",
        n,
        m,
        count,
        missed.size(),
        extras.size(),
        early,
        late,
        pitchPercent,
        timingPercent,
        durationPercent,
        rhythmPercent,
        timingMean,
        intonation,
        confidenceMean,
        List.copyOf(pairs),
        List.copyOf(missed),
        List.copyOf(extras));
  }

  private static void requireOrdered(double[] onsets) {
    for (int i = 1; i < onsets.length; i++)
      if (onsets[i] < onsets[i - 1])
        throw new IllegalArgumentException("Events must be ordered by onset");
  }
}
