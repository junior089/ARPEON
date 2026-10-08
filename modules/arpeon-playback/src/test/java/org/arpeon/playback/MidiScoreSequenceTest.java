package org.arpeon.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sound.midi.ShortMessage;
import org.arpeon.domain.instrument.InstrumentCatalog;
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

class MidiScoreSequenceTest {
  private static ScoreTimeline timeline() {
    var measure =
        new Measure(
            "1",
            new TimeSignature(2, 4),
            Optional.of(new Tempo(90)),
            List.of(
                new Note(Pitch.fromMidiNumber(69), new Duration(1, 4)),
                new Note(Pitch.fromMidiNumber(71), new Duration(1, 4))),
            false);
    return new ScoreTimeline(
        new Score("Playback", List.of(new Part("P1", "Flute", List.of(measure)))));
  }

  @Test
  void schedulesAudibleNotesAfterCountInAndIndependentClicks() {
    var sequence = MidiScoreSequence.build(timeline());
    assertEquals(4, sequence.getTracks().length);
    var part = sequence.getTracks()[1];
    var noteOn =
        java.util.stream.IntStream.range(0, part.size())
            .mapToObj(part::get)
            .filter(
                e ->
                    e.getMessage() instanceof ShortMessage m
                        && m.getCommand() == ShortMessage.NOTE_ON)
            .toList();
    assertEquals(2, noteOn.size());
    assertEquals(960, noteOn.getFirst().getTick());
    assertEquals(1440, noteOn.get(1).getTick());
    assertTrue(sequence.getTracks()[2].size() > 2);
    assertTrue(sequence.getTracks()[3].size() > 2);
  }

  @Test
  void controlsDoNotRequireAnAudioDeviceUntilPlay() {
    try (var player = new MidiScorePlayer(timeline())) {
      player.setMute("P1", true);
      player.setSolo("P1", true);
      player.setBpm(110);
      player.setMetronome(true);
      player.setCountIn(true);
      player.seek(480);
      var state = player.snapshot();
      assertEquals("STOPPED", state.status());
      assertEquals(480, state.tick());
      assertEquals(110, state.startingBpm());
      assertTrue(state.parts().getFirst().mute());
      assertTrue(state.parts().getFirst().solo());
      assertThrows(IllegalArgumentException.class, () -> player.seek(961));
    }
  }

  @Test
  void selectedFluteUsesItsGeneralMidiVoiceAndInstrumentTranspositionAffectsSoundingPitch() {
    var flute = MidiScoreSequence.build(timeline(), Map.of("P1", InstrumentCatalog.get("flute")));
    var fluteTrack = flute.getTracks()[1];
    var program = (ShortMessage) fluteTrack.get(0).getMessage();
    assertEquals(ShortMessage.PROGRAM_CHANGE, program.getCommand());
    assertEquals(73, program.getData1());
    var clarinet =
        MidiScoreSequence.build(timeline(), Map.of("P1", InstrumentCatalog.get("bb-clarinet")));
    var clarinetTrack = clarinet.getTracks()[1];
    assertEquals(71, ((ShortMessage) clarinetTrack.get(0).getMessage()).getData1());
    var note =
        java.util.stream.IntStream.range(0, clarinetTrack.size())
            .mapToObj(clarinetTrack::get)
            .map(event -> event.getMessage())
            .filter(
                message ->
                    message instanceof ShortMessage midi
                        && midi.getCommand() == ShortMessage.NOTE_ON)
            .map(message -> (ShortMessage) message)
            .findFirst()
            .orElseThrow();
    assertEquals(67, note.getData1(), "A4 written sounds G4 on a B-flat clarinet");
  }

  @Test
  void refusesTempoOutsideMidiEncodingInsteadOfPlayingAtWrongSpeed() {
    var measure =
        new Measure(
            "1",
            new TimeSignature(1, 4),
            Optional.of(new Tempo(1)),
            List.of(new Note(Pitch.fromMidiNumber(60), new Duration(1, 4))),
            false);
    var timeline =
        new ScoreTimeline(new Score("Slow", List.of(new Part("P1", "One", List.of(measure)))));
    assertThrows(IllegalArgumentException.class, () -> MidiScoreSequence.build(timeline));
  }
}
