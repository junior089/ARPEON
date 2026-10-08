package org.arpeon.domain.score;

import java.util.Objects;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.time.Duration;

/** Written pitch; an import cannot silently apply instrument transposition. */
public record Note(Pitch writtenPitch, Duration duration) implements ScoreEvent {
  public Note {
    Objects.requireNonNull(writtenPitch);
    Objects.requireNonNull(duration);
  }
}
