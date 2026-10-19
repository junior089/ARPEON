package org.arpeon.domain.score;

import java.util.Objects;
import org.arpeon.domain.time.Duration;

public record Rest(Duration duration, Tuplet tuplet, int staff) implements ScoreEvent {
  public Rest(Duration duration, Tuplet tuplet) {
    this(duration, tuplet, 1);
  }

  public Rest(Duration duration) {
    this(duration, null);
  }

  public Rest {
    Objects.requireNonNull(duration);
    if (staff < 1 || staff > 8) throw new IllegalArgumentException("Invalid staff number");
  }
}
