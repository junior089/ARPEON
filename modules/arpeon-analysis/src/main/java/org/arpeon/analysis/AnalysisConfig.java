package org.arpeon.analysis;

/** User-selected tolerances. They are evaluation criteria, not skill grades. */
public record AnalysisConfig(
    int alignmentWindowMs,
    int timingToleranceMs,
    int pitchToleranceCents,
    int rhythmTolerancePercent,
    int latencyCompensationMs) {
  public AnalysisConfig {
    if (alignmentWindowMs < 150
        || alignmentWindowMs > 1000
        || timingToleranceMs < 20
        || timingToleranceMs > alignmentWindowMs
        || pitchToleranceCents < 10
        || pitchToleranceCents > 100
        || rhythmTolerancePercent < 5
        || rhythmTolerancePercent > 60
        || latencyCompensationMs < 0
        || latencyCompensationMs > 500)
      throw new IllegalArgumentException("Analysis tolerances outside supported range");
  }
}
