package org.arpeon.domain.pitch;

public enum Accidental {
  DOUBLE_FLAT(-2),
  FLAT(-1),
  NATURAL(0),
  SHARP(1),
  DOUBLE_SHARP(2);

  private final int semitones;

  Accidental(int semitones) {
    this.semitones = semitones;
  }

  public int semitones() {
    return semitones;
  }

  public static Accidental fromSemitones(int semitones) {
    for (var value : values()) if (value.semitones == semitones) return value;
    throw new IllegalArgumentException("Only integral accidentals from -2 to 2 are supported");
  }
}
