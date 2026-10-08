package org.arpeon.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.time.Duration;
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
