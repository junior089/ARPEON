package org.arpeon.domain.score;

import java.util.List;
import java.util.Objects;

public record Score(
    String title,
    List<Part> parts,
    List<RepeatRegion> repeatRegions,
    List<EndingRegion> endingRegions) {
  public Score(String title, List<Part> parts) {
    this(title, parts, List.of(), List.of());
  }

  public Score {
    Objects.requireNonNull(title);
    parts = List.copyOf(parts);
    if (parts.isEmpty()) throw new IllegalArgumentException("Score must contain parts");
    if (parts.stream().map(Part::id).distinct().count() != parts.size())
      throw new IllegalArgumentException("Duplicate part IDs");
    repeatRegions = List.copyOf(repeatRegions);
    endingRegions = List.copyOf(endingRegions);
    int measureCount = parts.getFirst().measures().size();
    var orderedRepeats =
        repeatRegions.stream()
            .sorted(java.util.Comparator.comparingInt(RepeatRegion::startMeasure))
            .toList();
    int previousEnd = -1;
    for (RepeatRegion repeat : orderedRepeats) {
      if (repeat.endMeasure() >= measureCount || repeat.startMeasure() <= previousEnd)
        throw new IllegalArgumentException("Repeat regions must be in-range and non-overlapping");
      previousEnd = repeat.endMeasure();
    }
    for (EndingRegion ending : endingRegions) {
      if (ending.endMeasure() >= measureCount)
        throw new IllegalArgumentException("Ending region exceeds score");
      var repeat =
          repeatRegions.stream()
              .filter(
                  region ->
                      region.startMeasure() <= ending.startMeasure()
                          && region.endMeasure() >= ending.endMeasure())
              .findFirst()
              .orElseThrow(
                  () -> new IllegalArgumentException("Ending must belong to a repeat region"));
      if (ending.passes().stream().anyMatch(pass -> pass > repeat.passes()))
        throw new IllegalArgumentException("Ending pass exceeds repeat count");
    }
    var orderedEndings =
        endingRegions.stream()
            .sorted(java.util.Comparator.comparingInt(EndingRegion::startMeasure))
            .toList();
    for (int i = 1; i < orderedEndings.size(); i++)
      if (orderedEndings.get(i).startMeasure() <= orderedEndings.get(i - 1).endMeasure())
        throw new IllegalArgumentException("Ending regions must not overlap");
  }
}
