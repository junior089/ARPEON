package org.arpeon.dsp.pitch;

import org.arpeon.audio.AudioFrame;

/** YIN difference / cumulative mean normalization / first trough / parabolic refinement. */
public final class YinPitchDetector implements PitchDetector {
  public static final double MIN_HZ = 50;
  public static final double MAX_HZ = 2000;
  private static final double THRESHOLD = 0.15;
  private static final double RMS_GATE = 0.005;

  @Override
  public PitchDetection detect(AudioFrame frame) {
    float[] samples = frame.samples();
    double mean = 0;
    for (float sample : samples) mean += sample;
    mean /= samples.length;
    double energy = 0;
    for (float sample : samples) energy += (sample - mean) * (sample - mean);
    double rms = Math.sqrt(energy / samples.length);
    if (rms < RMS_GATE) return new PitchDetection(PitchDetection.State.SILENCE, 0, 0, rms);
    int window = samples.length / 2;
    int minimum = Math.max(2, (int) Math.floor(frame.sampleRateHz() / MAX_HZ));
    int maximum = Math.min(window - 1, (int) Math.ceil(frame.sampleRateHz() / MIN_HZ));
    if (maximum <= minimum)
      return new PitchDetection(PitchDetection.State.LOW_CONFIDENCE, 0, 0, rms);
    double[] normalized = new double[maximum + 1];
    double cumulative = 0;
    normalized[0] = 1;
    for (int lag = 1; lag <= maximum; lag++) {
      double difference = 0;
      for (int i = 0; i < window; i++) {
        double delta = samples[i] - samples[i + lag];
        difference += delta * delta;
      }
      cumulative += difference;
      normalized[lag] = cumulative == 0 ? 1 : difference * lag / cumulative;
    }
    for (int lag = minimum; lag < maximum; lag++) {
      if (normalized[lag] >= THRESHOLD) continue;
      while (lag + 1 < maximum && normalized[lag + 1] < normalized[lag]) lag++;
      double left = normalized[lag - 1], center = normalized[lag], right = normalized[lag + 1];
      double denominator = left - 2 * center + right;
      double adjustment = denominator == 0 ? 0 : 0.5 * (left - right) / denominator;
      double frequency = frame.sampleRateHz() / (lag + Math.clamp(adjustment, -1, 1));
      double confidence = Math.clamp(1 - center, 0, 1);
      if (frequency < MIN_HZ || frequency > MAX_HZ)
        return new PitchDetection(PitchDetection.State.LOW_CONFIDENCE, 0, confidence, rms);
      return new PitchDetection(PitchDetection.State.DETECTED, frequency, confidence, rms);
    }
    return new PitchDetection(PitchDetection.State.LOW_CONFIDENCE, 0, 0, rms);
  }
}
