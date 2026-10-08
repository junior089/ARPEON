package org.arpeon.tuner;

public record TuningReference(double a4Hz) {
  public TuningReference {
    if (!Double.isFinite(a4Hz) || a4Hz < 400 || a4Hz > 480)
      throw new IllegalArgumentException("A4 reference must be between 400 and 480 Hz");
  }
}
