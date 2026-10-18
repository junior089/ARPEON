package org.arpeon.domain.score;

import java.util.Set;

public record Clef(String sign, int line, int octaveChange) {
  public Clef {
    if (!Set.of("G", "F", "C", "percussion", "none").contains(sign)
        || line < 1
        || line > 5
        || octaveChange < -2
        || octaveChange > 2) throw new IllegalArgumentException("Unsupported clef");
  }
}
