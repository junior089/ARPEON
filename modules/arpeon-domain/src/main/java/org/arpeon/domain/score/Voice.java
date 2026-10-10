package org.arpeon.domain.score;

import java.util.ArrayList;
import java.util.List;
import org.arpeon.domain.time.ScoreOffset;

/** A stable, sequential voice whose events retain explicit measure-relative onsets. */
public record Voice(String id, List<TimedEvent> events, boolean explicitlyTimed) {
  public Voice(String id, List<TimedEvent> events) {
    this(id, events, true);
  }

  public Voice {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("Voice identity required");
    events = List.copyOf(events);
    if (events.isEmpty()) throw new IllegalArgumentException("Voice must contain events");
    ScoreOffset previousEnd = ScoreOffset.zero();
    for (TimedEvent timed : events) {
      if (timed.onset().compareTo(previousEnd) < 0)
        throw new IllegalArgumentException("Voice events overlap or are out of order");
      previousEnd = timed.onset().plus(timed.event().duration());
    }
  }

  public static Voice sequential(String id, List<ScoreEvent> events) {
    var timed = new ArrayList<TimedEvent>(events.size());
    ScoreOffset onset = ScoreOffset.zero();
    for (ScoreEvent event : events) {
      timed.add(new TimedEvent(onset, event));
      onset = onset.plus(event.duration());
    }
    return new Voice(id, timed, false);
  }

  public ScoreOffset end() {
    TimedEvent last = events.getLast();
    return last.onset().plus(last.event().duration());
  }

  public List<ScoreEvent> sequentialEvents() {
    var result = new ArrayList<ScoreEvent>(events.size());
    ScoreOffset expected = ScoreOffset.zero();
    for (TimedEvent timed : events) {
      if (!timed.onset().equals(expected))
        throw new IllegalStateException("Voice has an explicit gap; timed events are required");
      result.add(timed.event());
      expected = expected.plus(timed.event().duration());
    }
    return List.copyOf(result);
  }
}
