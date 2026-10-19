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
  @Test
  void namedMixesAndStudyMarkersRoundTripWithoutChangingPositionOrSelection() {
    try (var player = new MidiScorePlayer(timeline())) {
      player.setVolume("P1", .35);
      player.setPan("P1", -.6);
      player.setMute("P1", true);
      player.setMaster(.8);
      player.saveMixPreset("", "Estudar flauta");
      var preset = player.snapshot().mixPresets().getFirst();
      player.saveMarker("Final", 1, 1);
      player.seek(528);
      player.preset("reset");
      player.applyMixPreset(preset.id());
      var state = player.snapshot();
      assertEquals(528, state.tick());
      assertEquals("P1", state.activePartId());
      assertEquals(.35, state.parts().getFirst().volume());
      assertEquals(-.6, state.parts().getFirst().pan());
      assertTrue(state.parts().getFirst().mute());
      assertEquals(.8, state.master());
      try (var restored = new MidiScorePlayer(timeline())) {
        restored.restore(player.settings());
        assertEquals(player.settings(), restored.settings());
        restored.studyMarker(state.markers().getFirst().id());
        assertEquals(480, restored.snapshot().tick());
        assertEquals(1, restored.snapshot().loop().startMeasureIndex());
      }
      assertThrows(
          IllegalArgumentException.class, () -> player.saveMixPreset("", "Estudar flauta"));
      assertThrows(IllegalArgumentException.class, () -> player.saveMixPreset("", "\n"));
      player.deleteMixPreset(preset.id());
      assertTrue(player.snapshot().mixPresets().isEmpty());
    }
  }

  @Test
  void progressiveTempoFollowsTheMidiClockWithoutPositionPollingAndStopsAtTheTarget()
      throws Exception {
    try (var clock = MidiSystem.getSequencer(false)) {
      clock.open();
      try (var player = new MidiScorePlayer(timeline(), Map.of(), clock)) {
        player.setBpm(240);
        player.setLoop(0, 0, true);
        player.setTempoPractice(new MidiScorePlayer.TempoPractice(true, 7, 250, 1));
        var boundaries = new CountDownLatch(3);
        clock.addMetaEventListener(
            message -> {
              if (message.getType() == 0x7f) boundaries.countDown();
            });
        player.play();
        assertTrue(
            boundaries.await(4, TimeUnit.SECONDS),
            "MIDI clock must report loop boundaries without HTTP polling");
        var state = player.snapshot();
        assertEquals(250, state.startingBpm());
        assertFalse(state.tempoPractice().enabled());
        assertTrue(state.completedLoops() >= 2);
        assertEquals(2, player.automaticPreferenceRevision());
        player.stop();
        assertEquals(0, player.snapshot().completedLoops());
        player.setLoop(0, 0, false);
        assertThrows(
            IllegalArgumentException.class,
            () -> player.setTempoPractice(new MidiScorePlayer.TempoPractice(true, 5, 260, 1)));
      }
    }
  }

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

  @Test
  void mixerPresetsMultiSoloAndMasterNeverChangeTheDisplayedPartOrOtherVolumes() {
    var original = timeline().score().parts().getFirst();
    var second = new Part("P2", "Violin", original.measures());
    try (var player =
        new MidiScorePlayer(new ScoreTimeline(new Score("Duo", List.of(original, second))))) {
      player.setVolume("P1", .3);
      player.setVolume("P2", .8);
      player.setMaster(.5);
      player.setMute("P1", true);
      player.setSolo("P1", true);
      player.setSolo("P2", true);
      assertEquals(0, player.snapshot().parts().getFirst().effectiveGain());
      assertEquals(.4, player.snapshot().parts().get(1).effectiveGain(), 1e-9);
      player.setSolo("P1", false);
      player.setSolo("P2", false);
      assertTrue(player.snapshot().parts().getFirst().mute());
      player.selectPart("P2");
      player.preset("practice");
      assertEquals("P2", player.snapshot().activePartId());
      assertFalse(player.snapshot().parts().getFirst().mute());
      assertTrue(player.snapshot().parts().get(1).mute());
      player.preset("listen");
      assertTrue(player.snapshot().parts().get(1).solo());
      assertEquals(.8, player.snapshot().parts().get(1).volume());
      var settings = player.settings();
      player.preset("reset");
      assertEquals(1, player.snapshot().master());
      player.restore(settings);
      assertEquals(.5, player.snapshot().master());
      assertEquals(.8, player.snapshot().parts().get(1).volume());
      assertEquals("NOT_LOADED", player.snapshot().parts().get(1).soundStatus());
      assertThrows(IllegalArgumentException.class, () -> player.setVolume("P1", Double.NaN));
      assertThrows(IllegalArgumentException.class, () -> player.setMaster(1.1));
      assertThrows(IllegalArgumentException.class, () -> player.setPan("P1", -1.1));
      assertThrows(IllegalArgumentException.class, () -> player.setVolume("missing", .5));
    }
  }

  @Test
  void twoPartsKeepTheirMidiChannelsWhileMixerChangesDuringPlayback() throws Exception {
    var first = timeline().score().parts().getFirst();
    var duo =
        new ScoreTimeline(
            new Score("Duo", List.of(first, new Part("P2", "Violin", first.measures()))));
    try (var clock = MidiSystem.getSequencer(false)) {
      clock.open();
      var playedChannels = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
      var notes = new CountDownLatch(2);
      clock
          .getTransmitter()
          .setReceiver(
              new Receiver() {
                @Override
                public void send(javax.sound.midi.MidiMessage message, long timestamp) {
                  if (message instanceof ShortMessage note
                      && note.getCommand() == ShortMessage.NOTE_ON
                      && note.getData2() > 0) {
                    if (playedChannels.add(note.getChannel())) notes.countDown();
                  }
                }

                @Override
                public void close() {}
              });
      try (var player = new MidiScorePlayer(duo, Map.of(), clock)) {
        player.play();
        assertTrue(notes.await(2, TimeUnit.SECONDS));
        player.setVolume("P2", .2);
        player.setSolo("P2", true);
        assertTrue(clock.getTrackMute(1));
        assertFalse(clock.getTrackMute(2));
        assertEquals("P1", player.snapshot().activePartId());
        player.setSolo("P2", false);
        assertFalse(clock.getTrackMute(1));
        player.pause();
        player.seek(600);
        player.play();
        assertTrue(player.snapshot().tick() >= 600);
        player.stop();
        assertEquals(0, player.snapshot().tick());
      }
    }
  }

  @Test
  void missingSoundOnlyDisablesItsOwnPartAndCanBeRetried() throws Exception {
    var first = timeline().score().parts().getFirst();
    var duo =
        new ScoreTimeline(
            new Score("Duo", List.of(first, new Part("P2", "Violin", first.measures()))));
    var available = new java.util.concurrent.atomic.AtomicBoolean(false);
    var flute =
        new javax.sound.midi.Instrument(
            null, new javax.sound.midi.Patch(0, 73), "Flute", Object.class) {
          @Override
          public Object getData() {
            return new Object();
          }
        };
    var violin =
        new javax.sound.midi.Instrument(
            null, new javax.sound.midi.Patch(0, 40), "Violin", Object.class) {
          @Override
          public Object getData() {
            return new Object();
          }
        };
    var output =
        new Receiver() {
          @Override
          public void send(javax.sound.midi.MidiMessage message, long timestamp) {}

          @Override
          public void close() {}
        };
    var synth =
        (javax.sound.midi.Synthesizer)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {javax.sound.midi.Synthesizer.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "getAvailableInstruments" ->
                          available.get()
                              ? new javax.sound.midi.Instrument[] {flute, violin}
                              : new javax.sound.midi.Instrument[] {flute};
                      case "loadInstrument" -> true;
                      case "getReceiver" -> output;
                      case "getChannels" -> new javax.sound.midi.MidiChannel[16];
                      case "getLatency" -> 0L;
                      default -> null;
                    });
    try (var clock = MidiSystem.getSequencer(false)) {
      clock.open();
      try (var player =
          new MidiScorePlayer(
              duo,
              Map.of(
                  "P1",
                  org.arpeon.domain.instrument.InstrumentCatalog.get("flute"),
                  "P2",
                  org.arpeon.domain.instrument.InstrumentCatalog.get("violin")),
              clock,
              synth)) {
        assertEquals("READY", player.snapshot().parts().getFirst().soundStatus());
        assertEquals("ERROR", player.snapshot().parts().get(1).soundStatus());
        assertFalse(clock.getTrackMute(1));
        assertTrue(clock.getTrackMute(2));
        available.set(true);
        player.retrySounds();
        assertEquals("READY", player.snapshot().parts().get(1).soundStatus());
        assertFalse(clock.getTrackMute(2));
        assertEquals(2, player.snapshot().parts().size());
        player.setSoundBank(
            null, "a".repeat(64), "Banco indisponível", "Banco ausente neste computador");
        assertTrue(
            player.snapshot().parts().stream().allMatch(p -> p.soundStatus().equals("ERROR")));
        assertEquals("Banco ausente neste computador", player.snapshot().soundBankError());
        player.setSoundBank(null, "system", "Banco do sistema", "");
        assertTrue(
            player.snapshot().parts().stream().allMatch(p -> p.soundStatus().equals("READY")));
      }
    }
  }
}
