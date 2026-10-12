package org.arpeon.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.Tempo;
import org.arpeon.domain.time.TimeSignature;
import org.arpeon.transport.ScoreTimeline;
import org.junit.jupiter.api.Test;

class MidiScorePlayerTest {
  private static ScoreTimeline timeline() {
    var meter = new TimeSignature(1, 4);
    var note = new Note(Pitch.fromMidiNumber(69), new Duration(1, 4));
    return new ScoreTimeline(
        new Score(
            "Tempo change",
            List.of(
                new Part(
                    "P1",
                    "Flute",
                    List.of(
                        new Measure("1", meter, Optional.of(new Tempo(120)), List.of(note), false),
                        new Measure(
                            "2", meter, Optional.of(new Tempo(60)), List.of(note), false))))));
  }

  @Test
  void subtractsOutputLatencyAcrossATempoBoundary() {
    assertEquals(384, MidiScorePlayer.audibleTick(timeline(), 528, 200_000, 1));
  }

  @Test
  void accountsForUserTempoFactorWithoutMovingBeforeTheScoreStart() {
    assertEquals(96, MidiScorePlayer.audibleTick(timeline(), 480, 200_000, 2));
    assertEquals(0, MidiScorePlayer.audibleTick(timeline(), 48, 200_000, 1));
    assertEquals(528, MidiScorePlayer.audibleTick(timeline(), 528, 0, 1));
  }

  @Test
  void stoppedTransportKeepsTheExactSeekPositionWithoutOpeningAudioDevices() {
    try (var player = new MidiScorePlayer(timeline())) {
      player.seek(528);
      var state = player.snapshot();
      assertEquals(528, state.tick());
      assertEquals(528, state.audibleTick());
      assertEquals(60, state.audibleBpm());
      assertEquals(0, state.audioLatencyMs());
    }
  }

  @Test
  void loopValidatesBoundsAndStopReturnsToAWithoutOpeningAudioDevices() {
    try (var player = new MidiScorePlayer(timeline())) {
      player.setLoop(1, 1, true);
      var selected = player.snapshot();
      assertEquals(480, selected.loop().startTick());
      assertEquals(960, selected.loop().endTick());
      assertEquals(480, selected.tick());
      assertThrows(IllegalArgumentException.class, () -> player.setLoop(1, 0, true));
      assertThrows(IllegalArgumentException.class, () -> player.setLoop(0, 2, true));
      assertThrows(IllegalArgumentException.class, () -> player.seek(960));
      player.seek(700);
      player.stop();
      assertEquals(480, player.snapshot().tick());
      player.setLoop(1, 1, false);
      player.seek(0);
      assertNull(player.snapshot().loop());
    }
  }

  @Test
  void softwareClockRepeatsWithoutHttpPollingAndCanPauseResumeAndStop() throws Exception {
    try (var clock = MidiSystem.getSequencer(false)) {
      clock.open();
      var notes = new CountDownLatch(3);
      clock
          .getTransmitter()
          .setReceiver(
              new Receiver() {
                @Override
                public void send(javax.sound.midi.MidiMessage message, long timestamp) {
                  if (message instanceof ShortMessage note
                      && note.getCommand() == ShortMessage.NOTE_ON
                      && note.getData2() > 0
                      && note.getChannel() == 0) notes.countDown();
                }

                @Override
                public void close() {}
              });
      try (var player = new MidiScorePlayer(timeline(), Map.of(), clock)) {
        player.setBpm(300);
        player.setLoop(1, 1, true);
        assertEquals(timeline().countInTicks() + 480, clock.getLoopStartPoint());
        assertEquals(timeline().countInTicks() + 959, clock.getLoopEndPoint());
        player.play();
        assertTrue(
            notes.await(3, TimeUnit.SECONDS), "Three iterations must run without snapshot polling");
        assertEquals("PLAYING", player.snapshot().status());
        assertTrue(player.snapshot().tick() >= 480 && player.snapshot().tick() < 960);
        player.pause();
        assertEquals("PAUSED", player.snapshot().status());
        assertFalse(clock.isRunning());
        player.play();
        assertTrue(clock.isRunning());
        player.stop();
        assertEquals(480, player.snapshot().tick());
        player.setLoop(1, 1, false);
        assertEquals(0, clock.getLoopCount());
      }
    }
  }
}
