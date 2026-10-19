package org.arpeon.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import org.junit.jupiter.api.Test;

class MixerReceiverTest {
  @Test
  void changesSustainedGainAndPreservesItAcrossSeekControllerChasing() throws Exception {
    var received = new ArrayList<ShortMessage>();
    var output =
        new Receiver() {
          @Override
          public void send(MidiMessage message, long timestamp) {
            received.add((ShortMessage) message);
          }

          @Override
          public void close() {}
        };
    var time = new java.util.concurrent.atomic.AtomicLong();
    try (var mixer = new MixerReceiver(output, time::get, false)) {
      mixer.setChannel(0, .5, -1);
      mixer.setChannel(1, .8, 1);
      mixer.setMaster(.5);
      time.addAndGet(30_000_000);
      mixer.advanceRamps();
      assertEquals(32, last(received, 0, 11));
      assertEquals(51, last(received, 1, 11));
      mixer.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 11, 80), -1);
      assertEquals(20, last(received, 0, 11));
      mixer.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 121, 0), -1);
      assertEquals(32, last(received, 0, 11));
      assertEquals(0, last(received, 0, 10));
      assertEquals(127, last(received, 1, 10));
      mixer.setMaster(0);
      time.addAndGet(15_000_000);
      mixer.advanceRamps();
      assertEquals(16, last(received, 0, 11));
      time.addAndGet(15_000_000);
      mixer.advanceRamps();
      assertEquals(0, last(received, 0, 11));
      assertEquals(0, last(received, 1, 11));
    }
  }

  @Test
  void newDragTargetsContinueFromTheCurrentGainAndFinishAtTheLastValue() {
    var time = new java.util.concurrent.atomic.AtomicLong();
    var received = new ArrayList<ShortMessage>();
    var output =
        new Receiver() {
          @Override
          public void send(MidiMessage message, long timestamp) {
            received.add((ShortMessage) message);
          }

          @Override
          public void close() {}
        };
    try (var mixer = new MixerReceiver(output, time::get, false)) {
      mixer.setChannel(0, 0, 0);
      time.addAndGet(15_000_000);
      mixer.advanceRamps();
      assertEquals(64, last(received, 0, 11));
      mixer.setChannel(0, .8, 0);
      assertEquals(64, last(received, 0, 11));
      time.addAndGet(30_000_000);
      mixer.advanceRamps();
      assertEquals(102, last(received, 0, 11));
    }
  }

  private int last(ArrayList<ShortMessage> messages, int channel, int controller) {
    return messages.reversed().stream()
        .filter(m -> m.getChannel() == channel && m.getData1() == controller)
        .findFirst()
        .orElseThrow()
        .getData2();
  }
}
