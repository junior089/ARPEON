package org.arpeon.analysis;

/** Score note at fixed tempo, expressed relative to the planned first beat. */
public record ExpectedEvent(int index, int midi, double onsetMs, double durationMs) {
  public ExpectedEvent {
    if (index < 0
        || midi < 0
        || midi > 127
        || !Double.isFinite(onsetMs)
        || onsetMs < 0
        || !Double.isFinite(durationMs)
        || durationMs <= 0) throw new IllegalArgumentException("Invalid expected event");
  }
}
