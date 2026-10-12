package org.arpeon.audio;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

public final class JavaSoundAudioInput implements AudioInputPort {
  public static final int FRAME_SAMPLES = 4096;
  private final AtomicLong overruns = new AtomicLong();
  private volatile TargetDataLine activeLine;
  private Thread captureThread;
  private Map<String, Mixer.Info> discovered = Map.of();

  private static AudioFormat format(int rate) {
    return new AudioFormat(rate, 16, 1, true, false);
  }

  private static List<Integer> rates(Mixer mixer) {
    return List.of(48000, 44100).stream()
        .filter(
            rate -> mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, format(rate))))
        .toList();
  }

  @Override
  public synchronized List<AudioInputDevice> devices() {
    var devices = new ArrayList<AudioInputDevice>();
    var snapshot = new LinkedHashMap<String, Mixer.Info>();
    var duplicates = new LinkedHashMap<String, Integer>();
    var mixers = AudioSystem.getMixerInfo();
    for (int i = 0; i < mixers.length; i++) {
      var supported = rates(AudioSystem.getMixer(mixers[i]));
      if (!supported.isEmpty()) {
        String identity = descriptor(mixers[i]);
        int ordinal = duplicates.merge(identity, 1, Integer::sum) - 1;
        String id = deviceId(mixers[i], ordinal);
        snapshot.put(id, mixers[i]);
        devices.add(new AudioInputDevice(id, mixers[i].getName(), supported));
      }
    }
    discovered = Map.copyOf(snapshot);
    return List.copyOf(devices);
  }

  private static String descriptor(Mixer.Info info) {
    return info.getName()
        + "\u0000"
        + info.getVendor()
        + "\u0000"
        + info.getDescription()
        + "\u0000"
        + info.getVersion();
  }

  // Unrelated device insertions do not change an input's identity. Identical driver
  // descriptors have only an enumeration ordinal; Java Sound exposes no hardware serial.
  static String deviceId(Mixer.Info info, int ordinal) {
    return UUID.nameUUIDFromBytes(
            (descriptor(info) + "\u0000" + ordinal).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  @Override
  public synchronized void start(
      String deviceId, Consumer<AudioFrame> onFrame, Consumer<String> onFailure) {
    stop();
    var device = discovered.get(deviceId);
    if (device == null)
      throw new IllegalArgumentException("Input device no longer available. Refresh inputs.");
    Mixer mixer = AudioSystem.getMixer(device);
    TargetDataLine opened = null;
    for (int rate : rates(mixer)) {
      try {
        opened =
            (TargetDataLine) mixer.getLine(new DataLine.Info(TargetDataLine.class, format(rate)));
        opened.open(format(rate), FRAME_SAMPLES * 4);
        break;
      } catch (LineUnavailableException | IllegalArgumentException unavailable) {
        if (opened != null) opened.close();
        opened = null;
      } catch (SecurityException denied) {
        if (opened != null) opened.close();
        throw new IllegalStateException(
            "Microphone access denied. Enable access in system privacy settings.", denied);
      }
    }
    if (opened == null)
      throw new IllegalStateException(
          "Cannot open input at 48 or 44.1 kHz. Close other audio applications and retry.");
    TargetDataLine line = opened;
    try {
      line.start();
    } catch (RuntimeException failure) {
      line.close();
      throw failure;
    }
    activeLine = line;
    overruns.set(0);
    captureThread =
        Thread.ofPlatform()
            .name("arpeon-audio-capture")
            .daemon()
            .start(() -> capture(line, onFrame, onFailure));
  }

  private void capture(
      TargetDataLine line, Consumer<AudioFrame> onFrame, Consumer<String> onFailure) {
    byte[] bytes = new byte[FRAME_SAMPLES * 2];
    long sequence = 0;
    int rate = Math.round(line.getFormat().getSampleRate());
    try {
      while (activeLine == line) {
        if (line.available() >= line.getBufferSize() - bytes.length) overruns.incrementAndGet();
        int count = 0;
        while (count < bytes.length && activeLine == line) {
          int read = line.read(bytes, count, bytes.length - count);
          if (read <= 0) throw new IllegalStateException("Audio device stopped returning samples");
          count += read;
        }
        if (activeLine != line) break;
        float[] samples = new float[FRAME_SAMPLES];
        for (int i = 0; i < samples.length; i++)
          samples[i] = (short) ((bytes[2 * i] & 255) | (bytes[2 * i + 1] << 8)) / 32768.0f;
        var capturedAt =
            Instant.now()
                .minusNanos(Math.round((FRAME_SAMPLES + line.available() / 2.0) * 1e9 / rate));
        onFrame.accept(new AudioFrame(samples, rate, capturedAt, sequence++));
      }
    } catch (RuntimeException failure) {
      if (activeLine == line)
        onFailure.accept(
            "Audio input disconnected or capture failed. Reconnect it and refresh inputs.");
    } finally {
      line.close();
    }
  }

  @Override
  public synchronized void stop() {
    var line = activeLine;
    activeLine = null;
    if (line != null) {
      line.stop();
      line.flush();
      line.close();
    }
    var thread = captureThread;
    captureThread = null;
    if (thread != null && thread != Thread.currentThread()) {
      try {
        thread.join(2000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      if (thread.isAlive())
        throw new IllegalStateException("Audio driver did not stop within two seconds");
    }
  }

  @Override
  public long overruns() {
    return overruns.get();
  }

  @Override
  public void close() {
    stop();
  }
}
