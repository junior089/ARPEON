package org.arpeon.dsp.pitch;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Random;
import org.arpeon.audio.AudioFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class YinPitchDetectorTest {
  private final YinPitchDetector detector = new YinPitchDetector();

  @ParameterizedTest
  @CsvSource({"440,48000", "261.625565,48000", "442,44100", "65.4064,48000", "1760,44100"})
  void detectsSyntheticSines(double frequency, int sampleRate) {
    var detected = detector.detect(signal(frequency, sampleRate, false));
    assertEquals(PitchDetection.State.DETECTED, detected.state());
    // Two cents permits finite-window/interpolation error, below the UI's ±5-cent region.
    assertTrue(
        Math.abs(1200 * Math.log(detected.frequencyHz() / frequency) / Math.log(2)) < 2,
        detected.toString());
    assertTrue(detected.confidence() > .95);
  }

  @Test
  void detectsFundamentalWithHarmonics() {
    var detected = detector.detect(signal(440, 48000, true));
    assertEquals(PitchDetection.State.DETECTED, detected.state());
    assertEquals(440, detected.frequencyHz(), .5);
  }

  @Test
  void silenceAndDcAreNotNotes() {
    assertEquals(
        PitchDetection.State.SILENCE,
        detector.detect(new AudioFrame(new float[4096], 48000, Instant.EPOCH, 0)).state());
    float[] dc = new float[4096];
    java.util.Arrays.fill(dc, .3f);
    assertEquals(
        PitchDetection.State.SILENCE,
        detector.detect(new AudioFrame(dc, 48000, Instant.EPOCH, 0)).state());
  }

  @Test
  void seededNoiseIsLowConfidence() {
    var random = new Random(719);
    float[] samples = new float[4096];
    for (int i = 0; i < samples.length; i++) samples[i] = (random.nextFloat() - .5f) * .5f;
    var detected = detector.detect(new AudioFrame(samples, 48000, Instant.EPOCH, 0));
    assertEquals(PitchDetection.State.LOW_CONFIDENCE, detected.state());
    assertEquals(0, detected.frequencyHz());
  }

  private static AudioFrame signal(double hz, int rate, boolean harmonics) {
    float[] samples = new float[4096];
    for (int i = 0; i < samples.length; i++) {
      double phase = 2 * Math.PI * hz * i / rate;
      samples[i] =
          (float)
              (.4 * Math.sin(phase)
                  + (harmonics ? .2 * Math.sin(2 * phase) + .1 * Math.sin(3 * phase) : 0));
    }
    return new AudioFrame(samples, rate, Instant.EPOCH, 0);
  }
}
