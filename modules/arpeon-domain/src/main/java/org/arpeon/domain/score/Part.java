package org.arpeon.domain.score;

import java.util.List;

public record Part(String id, String name, List<Measure> measures) {
  public Part {
    if (id == null || id.isBlank() || name == null || name.isBlank())
      throw new IllegalArgumentException("Part identity required");
    measures = List.copyOf(measures);
    if (measures.isEmpty()) throw new IllegalArgumentException("Part must contain measures");
  }
}
