package org.arpeon.analysis;

/** One stable monophonic pitch segment; onset is the first trusted analysis window. */
public record ObservedEvent(
    int index,
    int midi,
    double onsetMs,
    double durationMs,
    double cents,
    double confidence,
    int samples) {
  public ObservedEvent {
    if (index < 0
        || midi < 0
        || midi > 127
        || !Double.isFinite(onsetMs)
        || onsetMs < 0
        || !Double.isFinite(durationMs)
        || durationMs <= 0
        || !Double.isFinite(cents)
        || !Double.isFinite(confidence)
        || confidence < 0
        || confidence > 1
        || samples < 1) throw new IllegalArgumentException("Invalid observed event");
  }
}
