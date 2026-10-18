package org.arpeon.playback;

import java.util.Arrays;
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

  MixerReceiver(Receiver output) {
    this.output = output;
    Arrays.fill(gains, 1);
    Arrays.fill(pans, 64);
    Arrays.fill(expression, 127);
  }

  synchronized void setChannel(int channel, double gain, double pan) {
    gains[channel] = gain;
    pans[channel] = (int) Math.round((pan + 1) * 63.5);
    control(channel, 11, effective(channel), -1);
    control(channel, 10, pans[channel], -1);
  }

  synchronized void setMaster(double value) {
    master = value;
    for (int channel = 0; channel < 16; channel++) control(channel, 11, effective(channel), -1);
  }

  private int effective(int channel) {
    return (int) Math.round(expression[channel] * gains[channel] * master);
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
    output.close();
  }
}
