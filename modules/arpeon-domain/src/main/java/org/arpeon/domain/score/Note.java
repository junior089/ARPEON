package org.arpeon.domain.score;

import java.util.Objects;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.time.Duration;

/** Written pitch; an import cannot silently apply instrument transposition. */
public record Note(
    Pitch writtenPitch,
    Duration duration,
    boolean tieStart,
    boolean tieStop,
    Tuplet tuplet,
    int staff)
    implements ScoreEvent {
  public Note(
      Pitch writtenPitch, Duration duration, boolean tieStart, boolean tieStop, Tuplet tuplet) {
    this(writtenPitch, duration, tieStart, tieStop, tuplet, 1);
  }

  public Note(Pitch writtenPitch, Duration duration, boolean tieStart, boolean tieStop) {
    this(writtenPitch, duration, tieStart, tieStop, null);
  }

  public Note(Pitch writtenPitch, Duration duration) {
    this(writtenPitch, duration, false, false);
  }

  public Note {
    Objects.requireNonNull(writtenPitch);
    Objects.requireNonNull(duration);
    if (staff < 1 || staff > 8) throw new IllegalArgumentException("Invalid staff number");
  }
}
