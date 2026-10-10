package org.arpeon.domain.score;

import java.util.List;
import org.arpeon.domain.time.Duration;

public sealed interface ScoreEvent permits Chord, Note, Rest {
  Duration duration();

  default List<Note> noteHeads() {
    if (this instanceof Note note) return List.of(note);
    if (this instanceof Chord chord) return chord.notes();
    return List.of();
  }
}
