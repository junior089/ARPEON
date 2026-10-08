package org.arpeon.domain.score;

import java.util.List;
import java.util.Objects;

public record Score(String title, List<Part> parts) {
  public Score {
    Objects.requireNonNull(title);
    parts = List.copyOf(parts);
    if (parts.isEmpty()) throw new IllegalArgumentException("Score must contain parts");
    if (parts.stream().map(Part::id).distinct().count() != parts.size())
      throw new IllegalArgumentException("Duplicate part IDs");
  }
}
