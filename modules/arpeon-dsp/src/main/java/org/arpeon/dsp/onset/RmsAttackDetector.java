package org.arpeon.dsp.onset;

/** Bounded RMS-rise onset candidate detector for successive mono PCM windows. */
public final class RmsAttackDetector {
  private static final double ABSOLUTE_GATE = 0.008;
  private static final double FLOOR_RATIO = 2.5;
  private static final double RISE_RATIO = 1.55;
  private static final long REFRACTORY_MS = 100;
  private static final long RESET_GAP_MS = 500;
  private static final double FLOOR_ALPHA = 0.04;

  private long lastTimestampMs = Long.MIN_VALUE;
  private long lastAttackMs = Long.MIN_VALUE;
  private double previousRms;
  private double noiseFloor = 0.003;

  /** Returns true for an abrupt, sufficiently loud rise, subject to a 100 ms refractory period. */
  public boolean accept(long timestampMs, double rms) {
    if (timestampMs < 0 || !Double.isFinite(rms) || rms < 0)
      throw new IllegalArgumentException("Invalid RMS onset window");
    if (timestampMs <= lastTimestampMs) return false;

    if (lastTimestampMs != Long.MIN_VALUE && timestampMs - lastTimestampMs > RESET_GAP_MS) {
      previousRms = 0;
      noiseFloor = Math.max(0.001, Math.min(noiseFloor, rms));
    }
    boolean candidate =
        rms >= ABSOLUTE_GATE
            && rms >= noiseFloor * FLOOR_RATIO
            && previousRms > 0
            && rms >= previousRms * RISE_RATIO
            && (lastAttackMs == Long.MIN_VALUE || timestampMs - lastAttackMs >= REFRACTORY_MS);

    if (!candidate && rms < noiseFloor * FLOOR_RATIO)
      noiseFloor += FLOOR_ALPHA * (rms - noiseFloor);
    previousRms = rms;
    lastTimestampMs = timestampMs;
    if (candidate) lastAttackMs = timestampMs;
    return candidate;
  }

  public void reset() {
    lastTimestampMs = Long.MIN_VALUE;
    lastAttackMs = Long.MIN_VALUE;
    previousRms = 0;
    noiseFloor = 0.003;
  }
}
