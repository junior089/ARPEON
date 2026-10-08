package org.arpeon.domain.time;

public record TimeSignature(int beats, int beatUnit) {
  public TimeSignature {
    if (beats < 1
        || beats > 64
        || beatUnit < 1
        || beatUnit > 128
        || Integer.bitCount(beatUnit) != 1)
      throw new IllegalArgumentException("Invalid time signature");
  }

  public Duration measureDuration() {
    return new Duration(beats, beatUnit);
  }
}
