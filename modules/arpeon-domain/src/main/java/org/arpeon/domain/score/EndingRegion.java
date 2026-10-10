package org.arpeon.domain.score;

import java.util.Set;

/** Inclusive written-measure range selected by the current repeat pass. */
public record EndingRegion(int startMeasure, int endMeasure, Set<Integer> passes) {
  public EndingRegion {
    if (startMeasure < 0 || endMeasure < startMeasure)
      throw new IllegalArgumentException("Invalid ending measure range");
    passes = Set.copyOf(passes);
    if (passes.isEmpty() || passes.stream().anyMatch(pass -> pass < 1 || pass > 8))
      throw new IllegalArgumentException("Ending pass numbers must be between 1 and 8");
  }
}
