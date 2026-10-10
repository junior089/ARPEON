package org.arpeon.domain.score;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.time.Duration;

/** A set of distinct note heads that sound together for one shared duration. */
public record Chord(List<Note> notes) implements ScoreEvent {
  public Chord {
    notes = List.copyOf(notes);
    if (notes.size() < 2) throw new IllegalArgumentException("A chord needs at least two notes");
    Duration duration = Objects.requireNonNull(notes.getFirst()).duration();
    var pitches = new HashSet<Pitch>();
    for (Note note : notes) {
      Objects.requireNonNull(note);
      if (!note.duration().equals(duration))
        throw new IllegalArgumentException("Chord notes must have the same duration");
      if (!pitches.add(note.writtenPitch()))
        throw new IllegalArgumentException("A chord cannot repeat the same written pitch");
    }
  }

  @Override
  public Duration duration() {
    return notes.getFirst().duration();
  }
}
