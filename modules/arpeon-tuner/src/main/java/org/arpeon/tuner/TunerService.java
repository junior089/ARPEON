package org.arpeon.tuner;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.arpeon.audio.AudioFrame;
import org.arpeon.audio.AudioInputDevice;
import org.arpeon.audio.AudioInputPort;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.dsp.onset.RmsAttackDetector;
import org.arpeon.dsp.pitch.PitchDetection;
import org.arpeon.dsp.pitch.PitchDetector;

public final class TunerService implements AutoCloseable {
  private final AudioInputPort input;
  private final PitchDetector detector;
  private final RmsAttackDetector attackDetector = new RmsAttackDetector();
  private final AtomicReference<TunerObservation> observation =
      new AtomicReference<>(
          TunerObservation.inactive(TunerObservation.State.IDLE, 440, "Microphone is off"));
  private final AtomicLong dropped = new AtomicLong();
  private final Deque<Long> attackTimestamps = new ArrayDeque<>();
  private volatile boolean running;
  private volatile long lastFrameNanos;
  private Thread analysisThread;
  private TuningReference reference = new TuningReference(440);

  public TunerService(AudioInputPort input, PitchDetector detector) {
    this.input = input;
    this.detector = detector;
  }

  public List<AudioInputDevice> devices() {
    return input.devices();
  }

  public TunerObservation observation() {
    return observation.get();
  }

  public synchronized boolean hasAttackBetween(long afterEpochMs, long throughEpochMs) {
    while (!attackTimestamps.isEmpty() && attackTimestamps.peekFirst() <= afterEpochMs)
      attackTimestamps.removeFirst();
    boolean found = false;
    while (!attackTimestamps.isEmpty() && attackTimestamps.peekFirst() <= throughEpochMs) {
      attackTimestamps.removeFirst();
      found = true;
    }
    return found;
  }

  public synchronized void start(String deviceId, TuningReference tuningReference) {
    stop();
    reference = tuningReference;
    dropped.set(0);
    attackDetector.reset();
    synchronized (this) {
      attackTimestamps.clear();
    }
    var queue = new ArrayBlockingQueue<AudioFrame>(2);
    running = true;
    lastFrameNanos = System.nanoTime();
    observation.set(
        TunerObservation.inactive(
            TunerObservation.State.STARTING, reference.a4Hz(), "Waiting for microphone samples"));
    analysisThread =
        Thread.ofPlatform()
            .name("arpeon-pitch-analysis")
            .daemon()
            .start(() -> analyze(queue, tuningReference));
    try {
      input.start(
          deviceId,
          frame -> {
            lastFrameNanos = System.nanoTime();
            if (!queue.offer(frame)) {
              queue.poll();
              dropped.incrementAndGet();
              queue.offer(frame);
            }
          },
          message ->
              observation.set(
                  TunerObservation.inactive(
                      TunerObservation.State.ERROR, tuningReference.a4Hz(), message)));
    } catch (RuntimeException failure) {
      stop();
      observation.set(
          TunerObservation.inactive(
              TunerObservation.State.ERROR, reference.a4Hz(), failure.getMessage()));
      throw failure;
    }
  }

  private void analyze(ArrayBlockingQueue<AudioFrame> queue, TuningReference tuningReference) {
    double previousHz = 0;
    int stableFrames = 0;
    try {
      while (running && !Thread.currentThread().isInterrupted()) {
        AudioFrame frame = queue.poll(250, TimeUnit.MILLISECONDS);
        if (frame == null) continue;
        long started = System.nanoTime();
        var detection = detector.detect(frame);
        long capturedAtMs = frame.capturedAt().toEpochMilli();
        if (attackDetector.accept(capturedAtMs, detection.rms())) recordAttack(capturedAtMs);
        double processingMs = (System.nanoTime() - started) / 1e6;
        TunerObservation.State state;
        Double hz = null, cents = null;
        String label = null;
        Integer octave = null;
        if (detection.state() == PitchDetection.State.SILENCE) {
          state = TunerObservation.State.SILENCE;
          stableFrames = 0;
        } else if (detection.state() == PitchDetection.State.LOW_CONFIDENCE) {
          state = TunerObservation.State.LOW_CONFIDENCE;
          stableFrames = 0;
        } else {
          double frequency = detection.frequencyHz();
          stableFrames =
              previousHz > 0 && Math.abs(centsBetween(frequency, previousHz)) < 20
                  ? stableFrames + 1
                  : 1;
          previousHz = frequency;
          state =
              stableFrames >= 3 ? TunerObservation.State.TRACKING : TunerObservation.State.UNSTABLE;
          if (state == TunerObservation.State.TRACKING) {
            var pitch = nearestPitch(frequency, tuningReference);
            hz = frequency;
            label = pitch.label();
            octave = pitch.octave();
            cents = centsBetween(frequency, pitch.frequencyHz(tuningReference.a4Hz()));
          }
        }
        double age =
            Math.max(
                0, java.time.Duration.between(frame.capturedAt(), Instant.now()).toNanos() / 1e6);
        var next =
            new TunerObservation(
                state,
                hz,
                label,
                octave,
                cents,
                detection.confidence(),
                frame.capturedAt(),
                frame.durationMs(),
                processingMs,
                age,
                dropped.get(),
                input.overruns(),
                frame.sampleRateHz(),
                tuningReference.a4Hz(),
                state.name());
        observation.updateAndGet(
            current ->
                current.state() == TunerObservation.State.ERROR || !running ? current : next);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException failure) {
      observation.set(
          TunerObservation.inactive(
              TunerObservation.State.ERROR,
              tuningReference.a4Hz(),
              "Pitch analysis failed. Stop and restart the tuner."));
    }
  }

  private synchronized void recordAttack(long timestampMs) {
    if (attackTimestamps.size() == 64) attackTimestamps.removeFirst();
    attackTimestamps.addLast(timestampMs);
  }

  public static double centsBetween(double frequency, double referenceHz) {
    if (!Double.isFinite(frequency)
        || !Double.isFinite(referenceHz)
        || frequency <= 0
        || referenceHz <= 0)
      throw new IllegalArgumentException("Frequencies must be positive and finite");
    return 1200 * Math.log(frequency / referenceHz) / Math.log(2);
  }

  public static Pitch nearestPitch(double frequency, TuningReference reference) {
    return Pitch.fromMidiNumber(
        Math.toIntExact(Math.round(69 + centsBetween(frequency, reference.a4Hz()) / 100)));
  }

  /** Called by the desktop lifecycle watchdog, outside the capture callback. */
  public synchronized void checkCaptureHealth() {
    if (!running) return;
    if (observation.get().state() == TunerObservation.State.ERROR
        || System.nanoTime() - lastFrameNanos > TimeUnit.SECONDS.toNanos(2)) {
      String message =
          observation.get().state() == TunerObservation.State.ERROR
              ? observation.get().message()
              : "No samples received for two seconds. Check microphone access and reconnect the"
                  + " input.";
      stop();
      observation.set(
          TunerObservation.inactive(TunerObservation.State.ERROR, reference.a4Hz(), message));
    }
  }

  public synchronized void stop() {
    running = false;
    var thread = analysisThread;
    analysisThread = null;
    if (thread != null) thread.interrupt();
    try {
      input.stop();
    } finally {
      if (thread != null) {
        try {
          thread.join(2000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) throw new IllegalStateException("Pitch analysis did not stop");
      }
    }
    observation.set(
        TunerObservation.inactive(
            TunerObservation.State.IDLE, reference.a4Hz(), "Microphone is off"));
  }

  @Override
  public void close() {
    stop();
    input.close();
  }
}
