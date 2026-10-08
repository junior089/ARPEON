package org.arpeon.tuner;

import java.time.Instant;

/** Null pitch fields mean no trustworthy note; latency excludes unknown hardware latency. */
public record TunerObservation(
    State state,
    Double frequencyHz,
    String nearestPitch,
    Integer octave,
    Double centsDeviation,
    double confidence,
    Instant capturedAt,
    double bufferLatencyMs,
    double processingLatencyMs,
    double analysisAgeMs,
    long droppedFrames,
    long inputOverruns,
    int sampleRateHz,
    double referenceHz,
    String message) {
  public enum State {
    IDLE,
    STARTING,
    SILENCE,
    LOW_CONFIDENCE,
    UNSTABLE,
    TRACKING,
    ERROR
  }

  public static TunerObservation inactive(State state, double reference, String message) {
    return new TunerObservation(
        state, null, null, null, null, 0, null, 0, 0, 0, 0, 0, 0, reference, message);
  }
}
