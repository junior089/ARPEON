package org.arpeon.domain.score;

import java.util.Objects;
import org.arpeon.domain.time.ScoreOffset;

/** One event at an explicit measure-relative onset. */
public record TimedEvent(ScoreOffset onset, ScoreEvent event) {
  public TimedEvent {
    Objects.requireNonNull(onset);
    Objects.requireNonNull(event);
  }
}
