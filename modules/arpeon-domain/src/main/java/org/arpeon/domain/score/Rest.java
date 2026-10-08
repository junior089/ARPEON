package org.arpeon.domain.score;

import java.util.Objects;
import org.arpeon.domain.time.Duration;

public record Rest(Duration duration) implements ScoreEvent {
  public Rest {
    Objects.requireNonNull(duration);
  }
}
