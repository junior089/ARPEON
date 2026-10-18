package org.arpeon.domain.score;

import java.util.Objects;
import org.arpeon.domain.time.Duration;

public record Rest(Duration duration, Tuplet tuplet) implements ScoreEvent {
  public Rest(Duration duration) {
    this(duration, null);
  }

  public Rest {
    Objects.requireNonNull(duration);
  }
}
