package org.arpeon.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.score.Chord;
import org.arpeon.domain.score.EndingRegion;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.RepeatRegion;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.score.TimedEvent;
import org.arpeon.domain.score.Voice;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.ScoreOffset;
import org.arpeon.domain.time.Tempo;
import org.arpeon.domain.time.TimeSignature;
import org.junit.jupiter.api.Test;

class ScoreTimelineTest {
  private static Score score() {
    var four = new TimeSignature(4, 4);
    var three = new TimeSignature(3, 4);
    var whole = new Duration(1, 1);
    var quarter = new Duration(1, 4);
    var first =
        new Measure(
            "1",
            four,
            Optional.of(new Tempo(60)),
            List.of(new Note(Pitch.fromMidiNumber(60), whole)),
            false);
    var second =
        new Measure(
            "2",
            three,
            Optional.of(new Tempo(120)),
            List.of(new Note(Pitch.fromMidiNumber(62), quarter), new Rest(new Duration(1, 2))),
            false);
    var accompaniment =
        new Part(
            "P2",
            "Accompaniment",
            List.of(
                new Measure("1", four, Optional.empty(), List.of(new Rest(whole)), false),
                new Measure(
                    "2", three, Optional.empty(), List.of(new Rest(new Duration(3, 4))), false)));
    return new Score(
        "Test", List.of(new Part("P1", "Melody", List.of(first, second)), accompaniment));
  }

  @Test
  void mapsTwoPartsAcrossTempoAndMeterChanges() {
    var timeline = new ScoreTimeline(score());
    assertEquals(1920, timeline.measures().get(1).startTick());
    assertEquals(3360, timeline.totalTicks());
    assertEquals(2, timeline.notes().size());
    assertEquals(60, timeline.notes().getFirst().midiNumber());
    assertEquals(1, timeline.measureIndexAt(1920));
    assertEquals(0, timeline.measureIndexAt(1919));
    assertEquals(60, timeline.bpmAt(1919));
    assertEquals(120, timeline.bpmAt(1920));
    assertEquals(4.5, timeline.secondsAt(2400), 0.0001);
    assertEquals(2400, timeline.tickAtSeconds(4.5));
    assertEquals(1920, timeline.countInTicks());
  }

  @Test
  void expandsWrittenRepeatWithPassSpecificEndingsInPlaybackOnly() {
    var meter = new TimeSignature(4, 4);
    var whole = new Duration(1, 1);
    var measures =
        List.of(
            new Measure(
                "1",
                meter,
                Optional.of(new Tempo(80)),
                List.of(new Note(Pitch.fromMidiNumber(60), whole)),
                false),
            new Measure(
                "2",
                meter,
                Optional.of(new Tempo(60)),
                List.of(new Note(Pitch.fromMidiNumber(62), whole)),
                false),
            new Measure(
                "3",
                meter,
                Optional.of(new Tempo(90)),
                List.of(new Note(Pitch.fromMidiNumber(64), whole)),
                false));
    var score =
        new Score(
            "Repeat",
            List.of(new Part("P1", "Melody", measures)),
            List.of(new RepeatRegion(0, 2, 2)),
            List.of(
                new EndingRegion(1, 1, java.util.Set.of(1)),
                new EndingRegion(2, 2, java.util.Set.of(2))));

    var occurrences = ExecutionMap.expand(score);
    var timeline = new ScoreTimeline(score);

    assertEquals(
        List.of(0, 1, 0, 2),
        occurrences.stream().map(ExecutionMap.Occurrence::measureIndex).toList());
    assertEquals(
        List.of(1, 1, 2, 2), occurrences.stream().map(ExecutionMap.Occurrence::pass).toList());
    assertEquals(
        List.of(0L, 1920L, 3840L, 5760L),
        timeline.measures().stream().map(ScoreTimeline.MeasurePosition::startTick).toList());
    assertEquals(
        List.of(60, 62, 60, 64),
        timeline.notes().stream().map(ScoreTimeline.NotePosition::midiNumber).toList());
    assertEquals(7680, timeline.totalTicks());
    assertEquals(
        List.of(80.0, 60.0, 80.0, 90.0),
        timeline.tempos().stream().map(ScoreTimeline.TempoPosition::bpm).toList());
    assertEquals(
        3, score.parts().getFirst().measures().size(), "written notation stays unexpanded");
  }

  @Test
  void keepsATiedNoteSustainedAcrossTheMeasureBoundary() {
    var four = new TimeSignature(4, 4);
    var quarter = new Duration(1, 4);
    var half = new Duration(1, 2);
    var melody =
        new Part(
            "P1",
            "Melody",
            List.of(
                new Measure(
                    "1",
                    four,
                    Optional.empty(),
                    List.of(
                        new Note(Pitch.fromMidiNumber(60), quarter),
                        new Note(Pitch.fromMidiNumber(62), quarter),
                        new Note(Pitch.fromMidiNumber(64), half, true, false)),
                    false),
                new Measure(
                    "2",
                    four,
                    Optional.empty(),
                    List.of(new Note(Pitch.fromMidiNumber(64), half, false, true), new Rest(half)),
                    false)));

    var notes = new ScoreTimeline(new Score("Tie", List.of(melody))).notes();
    assertEquals(3, notes.size());
    assertEquals(960, notes.get(2).startTick());
    assertEquals(1920, notes.get(2).durationTicks());
  }

  @Test
  void placesChordHeadsAtOneOnsetAndCountsTheirDurationOnce() {
    var quarter = new Duration(1, 4);
    var part =
        new Part(
            "P1",
            "Chord",
            List.of(
                new Measure(
                    "1",
                    new TimeSignature(4, 4),
                    Optional.empty(),
                    List.of(
                        new Chord(
                            List.of(
                                new Note(Pitch.fromMidiNumber(60), quarter),
                                new Note(Pitch.fromMidiNumber(64), quarter))),
                        new Rest(new Duration(3, 4))),
                    false)));

    var timeline = new ScoreTimeline(new Score("Chord", List.of(part)));
    assertEquals(1920, timeline.totalTicks());
    assertEquals(2, timeline.notes().size());
    assertEquals(
        List.of(0L, 0L),
        timeline.notes().stream().map(ScoreTimeline.NotePosition::startTick).toList());
    assertEquals(
        List.of(60, 64),
        timeline.notes().stream().map(ScoreTimeline.NotePosition::midiNumber).toList());
    assertEquals(
        List.of(480L, 480L),
        timeline.notes().stream().map(ScoreTimeline.NotePosition::durationTicks).toList());
  }

  @Test
  void mapsIndependentVoicesToTheirExplicitOnsets() {
    var quarter = new Duration(1, 4);
    var meter = new TimeSignature(4, 4);
    var melody =
        Voice.sequential(
            "1",
            List.of(
                new Note(Pitch.fromMidiNumber(60), quarter),
                new Note(Pitch.fromMidiNumber(62), quarter),
                new Rest(new Duration(1, 2))));
    var accompaniment =
        new Voice(
            "2",
            List.of(
                new TimedEvent(
                    new ScoreOffset(1, 2), new Note(Pitch.fromMidiNumber(67), quarter))));
    var measure =
        Measure.withVoices("1", meter, Optional.empty(), List.of(melody, accompaniment), false);

    var timeline =
        new ScoreTimeline(new Score("Voices", List.of(new Part("P1", "Piano", List.of(measure)))));

    assertEquals(3, timeline.notes().size());
    assertEquals(
        List.of(0L, 480L, 960L),
        timeline.notes().stream().map(ScoreTimeline.NotePosition::startTick).toList());
    assertEquals(
        List.of(60, 62, 67),
        timeline.notes().stream().map(ScoreTimeline.NotePosition::midiNumber).toList());
    assertEquals(1920, timeline.totalTicks());
  }

  @Test
  void rejectsConflictingPartTiming() {
    var original = score();
    var mismatch =
        new Score(
            "Test",
            List.of(
                original.parts().getFirst(),
                new Part("P2", "Wrong", List.of(original.parts().get(1).measures().getFirst()))));
    assertThrows(IllegalArgumentException.class, () -> new ScoreTimeline(mismatch));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScoreTimeline(
                new Score(
                    "Test",
                    List.of(
                        original.parts().getFirst(),
                        new Part(
                            "P2",
                            "Wrong tempo",
                            List.of(
                                new Measure(
                                    "1",
                                    new TimeSignature(4, 4),
                                    Optional.of(new Tempo(90)),
                                    List.of(new Rest(new Duration(1, 1))),
                                    false),
                                original.parts().get(1).measures().get(1)))))));
  }
}
