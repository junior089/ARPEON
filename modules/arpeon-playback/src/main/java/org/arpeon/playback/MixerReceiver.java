package org.arpeon.playback;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

/** Keeps mixer gain authoritative when the sequencer chases or resets MIDI controllers. */
final class MixerReceiver implements Receiver {
  private final Receiver output;
  private final double[] gains = new double[16];
  private final int[] pans = new int[16];
  private final int[] expression = new int[16];
  private double master = 1;
  private final double[] current = new double[16];
  private final double[] from = new double[16];
  private final double[] target = new double[16];
  private final long[] started = new long[16];
  private final LongSupplier nanoTime;
  private final ScheduledExecutorService ramps;
  private boolean closed;
  private static final long RAMP_NANOS = 30_000_000;

  MixerReceiver(Receiver output) {
    this(output, System::nanoTime, true);
  }

  MixerReceiver(Receiver output, LongSupplier nanoTime, boolean automatic) {
    this.output = output;
    this.nanoTime = nanoTime;
    Arrays.fill(gains, 1);
    Arrays.fill(pans, 64);
    Arrays.fill(expression, 127);
    Arrays.fill(current, 1);
    Arrays.fill(from, 1);
    Arrays.fill(target, 1);
    ramps =
        automatic
            ? Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                  var thread = new Thread(runnable, "arpeon-mixer-ramp");
                  thread.setDaemon(true);
                  return thread;
                })
            : null;
    if (ramps != null) ramps.scheduleAtFixedRate(this::advanceRamps, 5, 5, TimeUnit.MILLISECONDS);
  }

  synchronized void setChannel(int channel, double gain, double pan) {
    gains[channel] = gain;
    pans[channel] = (int) Math.round((pan + 1) * 63.5);
    ramp(channel);
    control(channel, 10, pans[channel], -1);
  }

  synchronized void setMaster(double value) {
    master = value;
    for (int channel = 0; channel < 16; channel++) ramp(channel);
  }

  private void ramp(int channel) {
    double next = gains[channel] * master;
    if (target[channel] == next) return;
    advanceRamps();
    from[channel] = current[channel];
    target[channel] = next;
    started[channel] = nanoTime.getAsLong();
  }

  synchronized void advanceRamps() {
    if (closed) return;
    long now = nanoTime.getAsLong();
    for (int channel = 0; channel < 16; channel++) {
      if (current[channel] == target[channel]) continue;
      double fraction = Math.min(1, Math.max(0, (double) (now - started[channel]) / RAMP_NANOS));
      current[channel] = from[channel] + (target[channel] - from[channel]) * fraction;
      control(channel, 11, effective(channel), -1);
    }
  }

  synchronized void finishRamps() {
    for (int channel = 0; channel < 16; channel++) {
      current[channel] = target[channel];
      from[channel] = target[channel];
      control(channel, 11, effective(channel), -1);
    }
  }

  private int effective(int channel) {
    return (int) Math.round(expression[channel] * current[channel]);
  }

  @Override
  public synchronized void send(MidiMessage message, long timestamp) {
    if (message instanceof ShortMessage shortMessage
        && shortMessage.getCommand() == ShortMessage.CONTROL_CHANGE) {
      int channel = shortMessage.getChannel();
      int controller = shortMessage.getData1();
      if (controller == 11) {
        expression[channel] = shortMessage.getData2();
        control(channel, 11, effective(channel), timestamp);
        return;
      }
      if (controller == 10) {
        control(channel, 10, pans[channel], timestamp);
        return;
      }
      if (controller == 121) {
        output.send(message, timestamp);
        expression[channel] = 127;
        control(channel, 11, effective(channel), timestamp);
        control(channel, 10, pans[channel], timestamp);
        return;
      }
    }
    output.send(message, timestamp);
  }

  private void control(int channel, int controller, int value, long timestamp) {
    try {
      output.send(
          new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, controller, value), timestamp);
    } catch (InvalidMidiDataException invalid) {
      throw new IllegalArgumentException("Invalid mixer controller", invalid);
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (ramps != null) ramps.shutdownNow();
    output.close();
  }
}
