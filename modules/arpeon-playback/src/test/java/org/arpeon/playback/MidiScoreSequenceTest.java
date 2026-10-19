package org.arpeon.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sound.midi.ShortMessage;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.score.Chord;
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
  @Test
  void aSharedPianoPitchDoesNotEndBeforeBothHandsReleaseIt() {
    var quarter = new Duration(1, 4);
    var measure =
        Measure.withVoices(
            "1",
            new TimeSignature(4, 4),
            Optional.empty(),
            List.of(
                org.arpeon.domain.score.Voice.sequential(
                    "1", List.of(new Note(Pitch.fromMidiNumber(60), new Duration(1, 1)))),
                org.arpeon.domain.score.Voice.sequential(
                    "2",
                    List.of(
                        new org.arpeon.domain.score.Rest(quarter, null, 2),
                        new Note(Pitch.fromMidiNumber(60), quarter, false, false, null, 2),
                        new org.arpeon.domain.score.Rest(new Duration(1, 2), null, 2)))),
            false);
    var score =
        new Score(
            "Unison", List.of(new Part("P1", "Piano", List.of(measure), Map.of(), 2, Map.of())));
    var timeline = new ScoreTimeline(score);
    var track = MidiScoreSequence.build(timeline).getTracks()[1];
    var events =
        java.util.stream.IntStream.range(0, track.size())
            .mapToObj(track::get)
            .filter(
                e ->
                    e.getMessage() instanceof ShortMessage m
                        && (m.getCommand() == ShortMessage.NOTE_ON
                            || m.getCommand() == ShortMessage.NOTE_OFF))
            .toList();
    assertEquals(4, events.size());
    assertEquals(
        List.of(1920L, 2400L, 3840L, 3840L),
        events.stream().map(javax.sound.midi.MidiEvent::getTick).toList());
    assertEquals(
        ShortMessage.NOTE_OFF, ((ShortMessage) events.getLast().getMessage()).getCommand());
  }

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
  void exportsTieChainAsOneSustainedMidiNote() {
    var meter = new TimeSignature(4, 4);
    var whole = new Duration(1, 1);
    var part =
        new Part(
            "P1",
            "Flute",
            List.of(
                new Measure(
                    "1",
                    meter,
                    Optional.empty(),
                    List.of(new Note(Pitch.fromMidiNumber(60), whole, true, false)),
                    false),
                new Measure(
                    "2",
                    meter,
                    Optional.empty(),
                    List.of(new Note(Pitch.fromMidiNumber(60), whole, false, true)),
                    false)));
    var sequence = MidiScoreSequence.build(new ScoreTimeline(new Score("Tied", List.of(part))));
    var track = sequence.getTracks()[1];
    var noteEvents =
        java.util.stream.IntStream.range(0, track.size())
            .mapToObj(track::get)
            .filter(
                event ->
                    event.getMessage() instanceof ShortMessage message
                        && (message.getCommand() == ShortMessage.NOTE_ON
                            || message.getCommand() == ShortMessage.NOTE_OFF))
            .toList();
    assertEquals(2, noteEvents.size());
    assertEquals(1920, noteEvents.getFirst().getTick());
    assertEquals(5760, noteEvents.get(1).getTick());
  }

  @Test
  void retriggersTiedNoteAtAAndReleasesItBeforeTheNativeLoopBoundary() {
    var meter = new TimeSignature(1, 4);
    var quarter = new Duration(1, 4);
    var pitch = Pitch.fromMidiNumber(60);
    var timeline =
        new ScoreTimeline(
            new Score(
                "Tied loop",
                List.of(
                    new Part(
                        "P1",
                        "Flute",
                        List.of(
                            new Measure(
                                "1",
                                meter,
                                Optional.empty(),
                                List.of(new Note(pitch, quarter, true, false)),
                                false),
                            new Measure(
                                "2",
                                meter,
                                Optional.empty(),
                                List.of(new Note(pitch, quarter, false, true)),
                                false))))));
    var loop = new MidiScorePlayer.LoopRange(480, 960, 1, 1);
    var sequence = MidiScoreSequence.build(timeline, Map.of(), loop);
    var track = sequence.getTracks()[1];
    var events =
        java.util.stream.IntStream.range(0, track.size())
            .mapToObj(track::get)
            .filter(
                event ->
                    event.getMessage() instanceof ShortMessage note
                        && (note.getCommand() == ShortMessage.NOTE_ON
                            || note.getCommand() == ShortMessage.NOTE_OFF))
            .toList();
    assertEquals(2, events.size());
    assertEquals(timeline.countInTicks() + 480, events.get(0).getTick());
    assertEquals(timeline.countInTicks() + 959, events.get(1).getTick());
    assertEquals(ShortMessage.NOTE_OFF, ((ShortMessage) events.get(1).getMessage()).getCommand());
  }

  @Test
  void schedulesChordHeadsAtTheSameMidiTick() {
    var duration = new Duration(1, 4);
    var measure =
        new Measure(
            "1",
            new TimeSignature(4, 4),
            Optional.empty(),
            List.of(
                new Chord(
                    List.of(
                        new Note(Pitch.fromMidiNumber(60), duration),
                        new Note(Pitch.fromMidiNumber(64), duration))),
                new org.arpeon.domain.score.Rest(new Duration(3, 4))),
            false);
    var sequence =
        MidiScoreSequence.build(
            new ScoreTimeline(
                new Score("Chord", List.of(new Part("P1", "Piano", List.of(measure))))));
    var track = sequence.getTracks()[1];
    var noteOns =
        java.util.stream.IntStream.range(0, track.size())
            .mapToObj(track::get)
            .filter(
                event ->
                    event.getMessage() instanceof ShortMessage message
                        && message.getCommand() == ShortMessage.NOTE_ON
                        && message.getChannel() == 0)
            .toList();
    assertEquals(2, noteOns.size());
    assertEquals(noteOns.get(0).getTick(), noteOns.get(1).getTick());
    assertEquals(1920, noteOns.getFirst().getTick());
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
