package org.arpeon.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PerformanceAnalyzerTest {
  private static final AnalysisConfig CONFIG = new AnalysisConfig(450, 120, 50, 20, 0);

  @Test
  void acceptsASelectedPartBeyondTheFormer256NoteLimit() {
    var expected = new java.util.ArrayList<ExpectedEvent>();
    for (int i = 0; i < 257; i++) expected.add(new ExpectedEvent(i, 60, i * 500.0, 400));
    assertEquals(
        "INSUFFICIENT_DATA", PerformanceAnalyzer.analyze(expected, List.of(), CONFIG).status());
    assertEquals(-1, new ScoreFollower(expected).position().noteIndex());
  }

  @Test
  void perfectNotesKeepMetricsSeparate() {
    var report =
        PerformanceAnalyzer.analyze(
            List.of(new ExpectedEvent(0, 69, 0, 400), new ExpectedEvent(1, 71, 500, 400)),
            List.of(
                new ObservedEvent(0, 69, 0, 400, 0, .9, 4),
                new ObservedEvent(1, 71, 500, 400, 0, .8, 4)),
            CONFIG);
    assertEquals(100, report.pitchAccuracyPercent());
    assertEquals(100, report.timingAccuracyPercent());
    assertEquals(100, report.durationAccuracyPercent());
    assertEquals(100, report.interOnsetAccuracyPercent());
    assertEquals(0, report.missedCount());
    assertEquals(0, report.extraCount());
    assertEquals(.85, report.meanMatchedConfidence(), .0001);
  }

  @Test
  void reportsPitchTimingRhythmMissesAndExtrasWithoutCompositeGrade() {
    var expected =
        List.of(
            new ExpectedEvent(0, 69, 0, 300),
            new ExpectedEvent(1, 71, 500, 300),
            new ExpectedEvent(2, 72, 1400, 300));
    var observed =
        List.of(
            new ObservedEvent(0, 69, 0, 300, 0, .9, 2),
            new ObservedEvent(1, 72, 700, 600, 10, .8, 2),
            new ObservedEvent(2, 77, 2200, 200, 0, .8, 2));
    var report = PerformanceAnalyzer.analyze(expected, observed, CONFIG);
    assertEquals(2, report.matchedCount());
    assertEquals(1, report.missedCount());
    assertEquals(1, report.extraCount());
    assertEquals(50, report.pitchAccuracyPercent());
    assertEquals(50, report.timingAccuracyPercent());
    assertEquals(0, report.interOnsetAccuracyPercent());
    assertEquals(1, report.lateCount());
    assertEquals(110, report.matches().get(1).centsError(), .01);
  }

  @Test
  void silenceDoesNotBecomeZeroAccuracy() {
    var report =
        PerformanceAnalyzer.analyze(List.of(new ExpectedEvent(0, 69, 0, 400)), List.of(), CONFIG);
    assertEquals("INSUFFICIENT_DATA", report.status());
    assertNull(report.pitchAccuracyPercent());
    assertNull(report.missedCount());
  }

  @Test
  void unmatchedObservedNoteHasCountsButNoAccuracyDenominator() {
    var report =
        PerformanceAnalyzer.analyze(
            List.of(new ExpectedEvent(0, 69, 0, 400)),
            List.of(new ObservedEvent(0, 71, 900, 400, 0, .8, 2)),
            CONFIG);
    assertEquals("ANALYZED", report.status());
    assertEquals(1, report.missedCount());
    assertEquals(1, report.extraCount());
    assertNull(report.pitchAccuracyPercent());
    assertNull(report.meanMatchedConfidence());
  }

  @Test
  void earlyEventAndLatencyCompensationAreExplicit() {
    var segmenter = new OnsetSegmenter(1_000, 440, 100);
    segmenter.accept(1_150, 440.0, .6, 85);
    segmenter.accept(1_250, 440.0, .9, 85);
    var observed = segmenter.finish();
    assertEquals(1, observed.size());
    assertEquals(150, observed.getFirst().onsetMs());
    var report =
        PerformanceAnalyzer.analyze(List.of(new ExpectedEvent(0, 69, 300, 85)), observed, CONFIG);
    assertEquals(1, report.earlyCount());
    assertEquals(-150, report.matches().getFirst().onsetErrorMs());
  }

  @Test
  void segmenterUsesCaptureTimestampsAndSplitsOnGap() {
    var segmenter = new OnsetSegmenter(1_000, 440, 20);
    segmenter.accept(950, 440.0, .9, 90);
    segmenter.accept(1_100, 440.0, .9, 90);
    segmenter.accept(1_200, 440.0, .9, 90);
    segmenter.accept(1_300, null, 0, 90);
    segmenter.accept(1_500, 493.883, .9, 90);
    var events = segmenter.finish();
    assertEquals(2, events.size());
    assertEquals(80, events.getFirst().onsetMs());
    assertEquals(69, events.getFirst().midi());
    assertEquals(71, events.get(1).midi());
    assertEquals(480, events.get(1).onsetMs());
  }
}
