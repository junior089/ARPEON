package org.arpeon.transport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.arpeon.domain.score.EndingRegion;
import org.arpeon.domain.score.RepeatRegion;
import org.arpeon.domain.score.Score;

/** Bounded mapping from written measures to performed occurrences. */
public final class ExecutionMap {
  public static final int MAX_OCCURRENCES = 100_000;

  public record Occurrence(int measureIndex, int pass, int repeatStartMeasure) {}

  private ExecutionMap() {}

  public static List<Occurrence> expand(Score score) {
    var repeats = new HashMap<Integer, RepeatRegion>();
    for (RepeatRegion repeat : score.repeatRegions()) repeats.put(repeat.startMeasure(), repeat);
    var endings = new HashMap<Integer, EndingRegion>();
    for (EndingRegion ending : score.endingRegions()) endings.put(ending.startMeasure(), ending);
    int measureCount = score.parts().getFirst().measures().size();
    var result = new ArrayList<Occurrence>();
    int measure = 0;
    while (measure < measureCount) {
      RepeatRegion repeat = repeats.get(measure);
      if (repeat == null) {
        append(result, new Occurrence(measure, 1, -1));
        measure++;
        continue;
      }
      for (int pass = 1; pass <= repeat.passes(); pass++) {
        int current = repeat.startMeasure();
        while (current <= repeat.endMeasure()) {
          EndingRegion ending = endings.get(current);
          if (ending != null && !ending.passes().contains(pass)) {
            current = ending.endMeasure() + 1;
            continue;
          }
          append(result, new Occurrence(current, pass, repeat.startMeasure()));
          current++;
        }
      }
      measure = repeat.endMeasure() + 1;
    }
    return List.copyOf(result);
  }

  private static void append(List<Occurrence> result, Occurrence occurrence) {
    if (result.size() >= MAX_OCCURRENCES)
      throw new IllegalArgumentException("Expanded score exceeds 100000 playback measures");
    result.add(occurrence);
  }
}
