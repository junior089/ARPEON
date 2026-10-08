package org.arpeon.audio;

import java.time.Instant;
import java.util.Objects;

/** Mono normalized PCM. capturedAt estimates the first sample's wall time. */
public record AudioFrame(float[] samples, int sampleRateHz, Instant capturedAt, long sequence) {
  public AudioFrame {
    samples = samples.clone();
    if (samples.length < 2
        || samples.length > 16384
        || sampleRateHz < 8000
        || sampleRateHz > 192000
        || sequence < 0) throw new IllegalArgumentException("Invalid audio frame");
    for (float sample : samples)
      if (!Float.isFinite(sample) || Math.abs(sample) > 1)
        throw new IllegalArgumentException("PCM samples must be finite and normalized");
    Objects.requireNonNull(capturedAt);
  }

  @Override
  public float[] samples() {
    return samples.clone();
  }

  public double durationMs() {
    return samples.length * 1000.0 / sampleRateHz;
  }
}
