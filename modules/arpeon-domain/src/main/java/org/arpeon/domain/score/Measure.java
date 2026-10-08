package org.arpeon.domain.score;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.arpeon.domain.time.Tempo;
import org.arpeon.domain.time.TimeSignature;

public record Measure(
    String number,
    TimeSignature timeSignature,
    Optional<Tempo> tempo,
    List<ScoreEvent> events,
    boolean pickup) {
  public Measure {
    if (number == null || number.isBlank())
      throw new IllegalArgumentException("Measure number required");
    Objects.requireNonNull(timeSignature);
    Objects.requireNonNull(tempo);
    events = List.copyOf(events);
    if (events.isEmpty()) throw new IllegalArgumentException("Measure must contain events");
    var total = events.stream().map(ScoreEvent::duration).reduce((a, b) -> a.plus(b)).orElseThrow();
    int comparison = total.compareTo(timeSignature.measureDuration());
    if (comparison > 0 || (!pickup && comparison != 0))
      throw new IllegalArgumentException("Measure duration does not match meter");
  }
}
