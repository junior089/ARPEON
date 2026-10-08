package org.arpeon.domain.score;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.TimeSignature;
import org.junit.jupiter.api.Test;

class ScoreDocumentTest {
  private static final Duration QUARTER = new Duration(1, 4);

  private static Score score() {
    return new Score(
        "Editing",
        List.of(
            new Part(
                "P1",
                "Flute",
                List.of(
                    new Measure(
                        "1",
                        new TimeSignature(4, 4),
                        Optional.empty(),
                        List.of(
                            new Note(new Pitch(PitchClass.D, Accidental.FLAT, 4), QUARTER),
                            new Rest(new Duration(1, 2)),
                            new Note(Pitch.fromMidiNumber(62), QUARTER)),
                        false)))));
  }

  @Test
  void editsKeepMeasuresFullAndHistoryNeverChangesTheImportedSource() {
    var document = new ScoreDocument(score());
    Score original = document.original();
    document.apply(
        current -> ScoreDocument.changeDuration(current, "P1", 0, 0, new Duration(3, 8)));
    var events = document.current().parts().getFirst().measures().getFirst().events();
    assertEquals(new Duration(3, 8), events.getFirst().duration());
    assertEquals(new Duration(3, 8), events.get(1).duration());
    document.apply(
        current ->
            ScoreDocument.insertIntoRest(current, "P1", 0, 1, QUARTER, Pitch.fromMidiNumber(64)));
    assertEquals(4, document.current().parts().getFirst().measures().getFirst().events().size());
    assertEquals(
        new Duration(1, 8),
        document.current().parts().getFirst().measures().getFirst().events().get(2).duration());
    assertThrows(
        IllegalArgumentException.class,
        () -> ScoreDocument.changeDuration(document.current(), "P1", 0, 0, new Duration(1, 1)));
    assertEquals(2, document.revision());
    document.undo();
    assertEquals(
        new Duration(3, 8),
        document.current().parts().getFirst().measures().getFirst().events().getFirst().duration());
    assertEquals(3, document.current().parts().getFirst().measures().getFirst().events().size());
    document.redo();
    assertEquals(
        new Duration(3, 8),
        document.current().parts().getFirst().measures().getFirst().events().getFirst().duration());
    document.apply(current -> ScoreDocument.deleteNote(current, "P1", 0, 0));
    assertInstanceOf(
        Rest.class,
        document.current().parts().getFirst().measures().getFirst().events().getFirst());
    assertFalse(document.canRedo());
    assertEquals(original, document.original());
    assertEquals(3, original.parts().getFirst().measures().getFirst().events().size());
  }

  @Test
  void explicitTargetKeyRespellsNotesAndKeepsRhythm() {
    Score shifted =
        ScoreTransform.toKey(score(), "P1", MusicalKey.get("C_MAJOR"), MusicalKey.get("Db_MAJOR"));
    var events = shifted.parts().getFirst().measures().getFirst().events();
    assertEquals("D4", ((Note) events.getFirst()).writtenPitch().label());
    assertEquals("Eb4", ((Note) events.get(2)).writtenPitch().label());
    assertEquals(
        score().parts().getFirst().measures().getFirst().events().stream()
            .map(ScoreEvent::duration)
            .toList(),
        events.stream().map(ScoreEvent::duration).toList());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ScoreTransform.toKey(
                score(), "P1", MusicalKey.get("C_MAJOR"), MusicalKey.get("A_MINOR")));
    assertEquals(0, MusicalKey.get("C#_MAJOR").spell(0).midiNumber());
  }

  @Test
  void octavePreviewAndInstrumentConversionExposeLimitsWithoutClamping() {
    var flute = InstrumentCatalog.get("flute");
    var low = ScoreTransform.transpose(score(), "P1", -12, null);
    var preview = ScoreTransform.previewOctave(low, "P1", flute, 12);
    assertEquals(2, preview.before().size());
    assertTrue(preview.after().isEmpty());
    assertEquals(
        "Db3",
        ((Note) low.parts().getFirst().measures().getFirst().events().getFirst())
            .writtenPitch()
            .label());
    var clarinet = InstrumentCatalog.get("bb-clarinet");
    var sounding = ScoreTransform.convertPitch(score(), "P1", clarinet, true);
    assertEquals(
        59,
        ((Note) sounding.parts().getFirst().measures().getFirst().events().getFirst())
            .writtenPitch()
            .midiNumber());
    var writtenAgain = ScoreTransform.convertPitch(sounding, "P1", clarinet, false);
    assertEquals(
        61,
        ((Note) writtenAgain.parts().getFirst().measures().getFirst().events().getFirst())
            .writtenPitch()
            .midiNumber());
  }
}
