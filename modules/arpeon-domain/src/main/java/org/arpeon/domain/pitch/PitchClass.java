package org.arpeon.domain.pitch;

public enum PitchClass {
  C(0),
  D(2),
  E(4),
  F(5),
  G(7),
  A(9),
  B(11);

  private final int semitones;

  PitchClass(int semitones) {
    this.semitones = semitones;
  }

  public int semitones() {
    return semitones;
  }
}
