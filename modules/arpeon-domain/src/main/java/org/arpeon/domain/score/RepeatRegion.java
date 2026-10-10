package org.arpeon.domain.score;

/** Inclusive written-measure range performed a bounded number of times. */
public record RepeatRegion(int startMeasure, int endMeasure, int passes) {
  public RepeatRegion {
    if (startMeasure < 0 || endMeasure < startMeasure)
      throw new IllegalArgumentException("Invalid repeat measure range");
    if (passes < 2 || passes > 8)
      throw new IllegalArgumentException("Repeat passes must be between 2 and 8");
  }
}
