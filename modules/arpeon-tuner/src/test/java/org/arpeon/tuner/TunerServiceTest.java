package org.arpeon.tuner;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.arpeon.audio.AudioFrame;
import org.arpeon.audio.AudioInputDevice;
import org.arpeon.audio.AudioInputPort;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.junit.jupiter.api.Test;

class TunerServiceTest {
  @Test
  void mapsDetuningAndConfigurableConcertReference() {
    assertEquals(69, TunerService.nearestPitch(442, new TuningReference(442)).midiNumber());
    assertEquals(0, TunerService.centsBetween(442, 442));
    assertEquals(7.8514, TunerService.centsBetween(442, 440), .001);
    assertThrows(IllegalArgumentException.class, () -> new TuningReference(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> TunerService.centsBetween(0, 440));
  }

  @Test
  void capturesAnalysesStabilizesClearsSilenceAndStops() throws InterruptedException {
    var input = new ManualInput();
    try (var tuner = new TunerService(input, new YinPitchDetector())) {
      tuner.start("test", new TuningReference(442));
      for (int frame = 0; frame < 3; frame++) {
        var audio = sine(442, frame);
        input.frames.accept(audio);
        await(() -> audio.capturedAt().equals(tuner.observation().capturedAt()));
        assertEquals(
            frame < 2 ? TunerObservation.State.UNSTABLE : TunerObservation.State.TRACKING,
            tuner.observation().state());
      }
      assertEquals("A4", tuner.observation().nearestPitch());
      assertEquals(0, tuner.observation().centsDeviation(), 1);
      assertEquals(48000, tuner.observation().sampleRateHz());
      assertTrue(tuner.observation().bufferLatencyMs() > 80);
      input.frames.accept(new AudioFrame(new float[4096], 48000, Instant.now(), 4));
      await(() -> tuner.observation().state() == TunerObservation.State.SILENCE);
      assertNull(tuner.observation().nearestPitch());
      tuner.stop();
      assertFalse(input.open);
      assertEquals(TunerObservation.State.IDLE, tuner.observation().state());
      tuner.start("test", new TuningReference(440));
      input.failure.accept("Disconnected");
      tuner.checkCaptureHealth();
      assertFalse(input.open);
      assertEquals(TunerObservation.State.ERROR, tuner.observation().state());
    }
  }

  @Test
  void failedStartReleasesCaptureAndPreservesFailureState() {
    var input = new ManualInput();
    try (var tuner = new TunerService(input, new YinPitchDetector())) {
      assertThrows(
          IllegalArgumentException.class, () -> tuner.start("missing", new TuningReference(440)));
      assertFalse(input.open);
      assertEquals(TunerObservation.State.ERROR, tuner.observation().state());
    }
  }

  @Test
  void boundedQueueDropsFramesWithoutBlockingCapture() throws InterruptedException {
    var input = new ManualInput();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var yin = new YinPitchDetector();
    try (var tuner =
        new TunerService(
            input,
            frame -> {
              entered.countDown();
              try {
                if (!release.await(2, TimeUnit.SECONDS))
                  throw new IllegalStateException("Test detector timed out");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              return yin.detect(frame);
            })) {
      tuner.start("test", new TuningReference(440));
      input.frames.accept(sine(440, 0));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      for (int i = 1; i < 20; i++) input.frames.accept(sine(440, i));
      release.countDown();
      await(() -> tuner.observation().droppedFrames() > 0);
      assertTrue(input.open);
    } finally {
      release.countDown();
    }
  }

  private static AudioFrame sine(double hz, int sequence) {
    float[] samples = new float[4096];
    for (int i = 0; i < samples.length; i++)
      samples[i] = (float) (.5 * Math.sin(2 * Math.PI * hz * i / 48000));
    return new AudioFrame(samples, 48000, Instant.now(), sequence);
  }

  private static void await(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline)
      TimeUnit.MILLISECONDS.sleep(5);
    assertTrue(condition.getAsBoolean(), "Timed out waiting for analysis");
  }

  private static final class ManualInput implements AudioInputPort {
    Consumer<AudioFrame> frames;
    Consumer<String> failure;
    boolean open;

    @Override
    public List<AudioInputDevice> devices() {
      return List.of(new AudioInputDevice("test", "Synthetic test input", List.of(48000)));
    }

    @Override
    public void start(String id, Consumer<AudioFrame> onFrame, Consumer<String> onFailure) {
      if (!id.equals("test")) throw new IllegalArgumentException("Missing device");
      open = true;
      frames = onFrame;
      failure = onFailure;
    }

    @Override
    public void stop() {
      open = false;
    }

    @Override
    public long overruns() {
      return 0;
    }

    @Override
    public void close() {
      stop();
    }
  }
}
