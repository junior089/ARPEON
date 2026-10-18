package org.arpeon.domain.score;

import java.util.Objects;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.time.Duration;

/** Written pitch; an import cannot silently apply instrument transposition. */
public record Note(
    Pitch writtenPitch, Duration duration, boolean tieStart, boolean tieStop, Tuplet tuplet)
    implements ScoreEvent {
  public Note(Pitch writtenPitch, Duration duration, boolean tieStart, boolean tieStop) {
    this(writtenPitch, duration, tieStart, tieStop, null);
  }

  public Note(Pitch writtenPitch, Duration duration) {
    this(writtenPitch, duration, false, false);
  }

  public Note {
    Objects.requireNonNull(writtenPitch);
    Objects.requireNonNull(duration);
  }
}
