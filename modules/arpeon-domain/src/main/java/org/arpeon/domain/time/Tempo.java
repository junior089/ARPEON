package org.arpeon.domain.time;

/** Quarter notes per minute, never an implicit transport clock. */
public record Tempo(double quarterNotesPerMinute) {
  public Tempo {
    if (!Double.isFinite(quarterNotesPerMinute)
        || quarterNotesPerMinute <= 0
        || quarterNotesPerMinute > 1000)
      throw new IllegalArgumentException("Tempo must be in (0, 1000]");
  }
}
