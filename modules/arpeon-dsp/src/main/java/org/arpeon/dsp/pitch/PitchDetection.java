package org.arpeon.dsp.pitch;

public record PitchDetection(State state, double frequencyHz, double confidence, double rms) {
  public enum State {
    SILENCE,
    LOW_CONFIDENCE,
    DETECTED
  }

  public PitchDetection {
    if (state == null
        || !Double.isFinite(frequencyHz)
        || frequencyHz < 0
        || !Double.isFinite(confidence)
        || confidence < 0
        || confidence > 1
        || !Double.isFinite(rms)
        || rms < 0) throw new IllegalArgumentException("Invalid pitch detection");
    if ((state == State.DETECTED) != (frequencyHz > 0))
      throw new IllegalArgumentException("Pitch/state mismatch");
  }
}
