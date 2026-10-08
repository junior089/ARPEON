package org.arpeon.audio;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AudioFrameTest {
  @Test
  void ownsSamplesAndReportsWindowInMilliseconds() {
    float[] source = new float[480];
    var frame = new AudioFrame(source, 48000, Instant.EPOCH, 0);
    source[0] = 1;
    frame.samples()[0] = 1;
    assertEquals(0, frame.samples()[0]);
    assertEquals(10, frame.durationMs());
  }

  @Test
  void rejectsInvalidCapture() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AudioFrame(new float[] {0, Float.NaN}, 48000, Instant.EPOCH, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AudioFrame(new float[] {0, 2}, 48000, Instant.EPOCH, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new AudioFrame(new float[2], 0, Instant.EPOCH, 0));
  }

  @Test
  void stoppingAnUnopenedDeviceIsIdempotent() {
    try (var input = new JavaSoundAudioInput()) {
      input.stop();
      input.stop();
      assertEquals(0, input.overruns());
    }
  }
}
