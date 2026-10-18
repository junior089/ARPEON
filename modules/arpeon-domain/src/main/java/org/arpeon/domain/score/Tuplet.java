package org.arpeon.domain.score;

public record Tuplet(int actual, int normal, boolean start, boolean stop) {
  public Tuplet {
    if (actual < 1 || actual > 32 || normal < 1 || normal > 32)
      throw new IllegalArgumentException("Tuplet ratio must be between 1 and 32");
  }
}
