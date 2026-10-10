package org.arpeon.domain.score;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.ScoreOffset;
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
  void chordDurationAndPitchTransformsApplyToAllHeadsTogether() {
    var chord =
        new Chord(
            List.of(
                new Note(Pitch.fromMidiNumber(60), QUARTER),
                new Note(Pitch.fromMidiNumber(64), QUARTER)));
    var measure =
        new Measure(
            "1",
            new TimeSignature(4, 4),
            Optional.empty(),
            List.of(chord, new Rest(new Duration(3, 4))),
            false);
    var original = new Score("Chord", List.of(new Part("P1", "Piano", List.of(measure))));
    var durationChanged = ScoreDocument.changeDuration(original, "P1", 0, 0, new Duration(1, 2));
    var changedChord =
        (Chord) durationChanged.parts().getFirst().measures().getFirst().events().getFirst();
    assertEquals(
        List.of(new Duration(1, 2), new Duration(1, 2)),
        changedChord.notes().stream().map(Note::duration).toList());
    assertEquals(
        new Duration(1, 2),
        durationChanged.parts().getFirst().measures().getFirst().events().get(1).duration());

    var transposed = ScoreTransform.transpose(durationChanged, "P1", 12, null);
    var transposedChord =
        (Chord) transposed.parts().getFirst().measures().getFirst().events().getFirst();
    assertEquals(
        List.of("C5", "E5"),
        transposedChord.notes().stream().map(n -> n.writtenPitch().label()).toList());

    var oneHeadChanged =
        (Chord)
            ScoreDocument.setPitch(original, "P1", 0, 0, 1, Pitch.fromMidiNumber(65))
                .parts()
                .getFirst()
                .measures()
                .getFirst()
                .events()
                .getFirst();
    assertEquals(
        List.of("C4", "F4"),
        oneHeadChanged.notes().stream().map(n -> n.writtenPitch().label()).toList());
    var oneHeadRemoved =
        ScoreDocument.deleteNote(original, "P1", 0, 0, 0)
            .parts()
            .getFirst()
            .measures()
            .getFirst()
            .events()
            .getFirst();
    assertEquals("E4", ((Note) oneHeadRemoved).writtenPitch().label());
  }

  @Test
  void editsAnIndependentVoiceWithoutChangingItsSiblingsOrOffsets() {
    var meter = new TimeSignature(4, 4);
    var voiceOne =
        Voice.sequential(
            "1",
            List.of(new Note(Pitch.fromMidiNumber(60), QUARTER), new Rest(new Duration(3, 4))));
    var voiceTwo =
        new Voice(
            "2",
            List.of(
                new TimedEvent(ScoreOffset.zero(), new Note(Pitch.fromMidiNumber(64), QUARTER))));
    var measure =
        Measure.withVoices("1", meter, Optional.empty(), List.of(voiceOne, voiceTwo), false);
    var original = new Score("Two voices", List.of(new Part("P1", "Piano", List.of(measure))));

    var pitched =
        ScoreDocument.setPitch(
            original, "P1", 0, 0, "2", 0, new Pitch(PitchClass.F, Accidental.SHARP, 4));
    var afterPitch = pitched.parts().getFirst().measures().getFirst();
    assertEquals(
        "C4",
        ((Note) afterPitch.voices().getFirst().events().getFirst().event()).writtenPitch().label());
    assertEquals(
        "F#4",
        ((Note) afterPitch.voices().get(1).events().getFirst().event()).writtenPitch().label());
    assertEquals(ScoreOffset.zero(), afterPitch.voices().get(1).events().getFirst().onset());

    var lengthened = ScoreDocument.changeDuration(pitched, "P1", 0, 0, "2", new Duration(1, 2));
    assertEquals(
        new Duration(1, 2),
        lengthened
            .parts()
            .getFirst()
            .measures()
            .getFirst()
            .voices()
            .get(1)
            .events()
            .getFirst()
            .event()
            .duration());
    var deleted = ScoreDocument.deleteNote(lengthened, "P1", 0, 0, "2", 0);
    assertInstanceOf(
        Rest.class,
        deleted
            .parts()
            .getFirst()
            .measures()
            .getFirst()
            .voices()
            .get(1)
            .events()
            .getFirst()
            .event());
  }

  @Test
  void chordHeadTieEditsPropagateOnlyThroughThatHeadChain() {
    var half = new Duration(1, 2);
    var meter = new TimeSignature(4, 4);
    var part =
        new Part(
            "P1",
            "Piano",
            List.of(
                new Measure(
                    "1",
                    meter,
                    Optional.empty(),
                    List.of(
                        new Chord(
                            List.of(
                                new Note(Pitch.fromMidiNumber(60), half, true, false),
                                new Note(Pitch.fromMidiNumber(64), half))),
                        new Chord(
                            List.of(
                                new Note(Pitch.fromMidiNumber(60), half, false, true),
                                new Note(Pitch.fromMidiNumber(67), half)))),
                    false),
                new Measure(
                    "2", meter, Optional.empty(), List.of(new Rest(new Duration(1, 1))), false)));
    var original = new Score("Tied chord", List.of(part));
    var changed = ScoreDocument.setPitch(original, "P1", 0, 0, 0, Pitch.fromMidiNumber(62));
    var firstChord = (Chord) changed.parts().getFirst().measures().getFirst().events().getFirst();
    var secondChord = (Chord) changed.parts().getFirst().measures().getFirst().events().get(1);
    assertEquals("D4", firstChord.notes().getFirst().writtenPitch().label());
    assertEquals("D4", secondChord.notes().getFirst().writtenPitch().label());
    assertEquals("E4", firstChord.notes().get(1).writtenPitch().label());
    assertEquals("G4", secondChord.notes().get(1).writtenPitch().label());

    var untied = ScoreDocument.removeTie(original, "P1", 0, 0, 0);
    var untiedFirst = (Chord) untied.parts().getFirst().measures().getFirst().events().getFirst();
    var untiedSecond = (Chord) untied.parts().getFirst().measures().getFirst().events().get(1);
    assertFalse(untiedFirst.notes().getFirst().tieStart());
    assertFalse(untiedSecond.notes().getFirst().tieStop());
    assertFalse(untiedFirst.notes().get(1).tieStart());
  }

  @Test
  void removingOneTieSelectionClearsTheEntireCrossMeasureChain() {
    var whole = new Duration(1, 1);
    var meter = new TimeSignature(4, 4);
    var tied =
        new Score(
            "Tied",
            List.of(
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
                            false)))));

    Score untied = ScoreDocument.removeTie(tied, "P1", 1, 0);
    assertFalse(
        ((Note) untied.parts().getFirst().measures().getFirst().events().getFirst()).tieStart());
    assertFalse(((Note) untied.parts().getFirst().measures().get(1).events().getFirst()).tieStop());

    Score transposed = ScoreDocument.setPitch(tied, "P1", 1, 0, Pitch.fromMidiNumber(62));
    assertEquals(
        "D4",
        ((Note) transposed.parts().getFirst().measures().getFirst().events().getFirst())
            .writtenPitch()
            .label());
    assertEquals(
        "D4",
        ((Note) transposed.parts().getFirst().measures().get(1).events().getFirst())
            .writtenPitch()
            .label());
  }

  @Test
  void removingATieInOneVoicePreservesTheOtherVoiceChain() {
    var whole = new Duration(1, 1);
    var meter = new TimeSignature(4, 4);
    var first =
        Measure.withVoices(
            "1",
            meter,
            Optional.empty(),
            List.of(
                Voice.sequential(
                    "1", List.of(new Note(Pitch.fromMidiNumber(60), whole, true, false))),
                Voice.sequential(
                    "2", List.of(new Note(Pitch.fromMidiNumber(64), whole, true, false)))),
            false);
    var second =
        Measure.withVoices(
            "2",
            meter,
            Optional.empty(),
            List.of(
                Voice.sequential(
                    "1", List.of(new Note(Pitch.fromMidiNumber(60), whole, false, true))),
                Voice.sequential(
                    "2", List.of(new Note(Pitch.fromMidiNumber(64), whole, false, true)))),
            false);
    var score = new Score("Two voices", List.of(new Part("P1", "Piano", List.of(first, second))));

    var edited = ScoreDocument.removeTie(score, "P1", 0, 0, "2", 0);

    assertTrue(
        ((Note)
                edited
                    .parts()
                    .getFirst()
                    .measures()
                    .getFirst()
                    .voices()
                    .getFirst()
                    .events()
                    .getFirst()
                    .event())
            .tieStart());
    assertTrue(
        ((Note)
                edited
                    .parts()
                    .getFirst()
                    .measures()
                    .get(1)
                    .voices()
                    .getFirst()
                    .events()
                    .getFirst()
                    .event())
            .tieStop());
    assertFalse(
        ((Note)
                edited
                    .parts()
                    .getFirst()
                    .measures()
                    .getFirst()
                    .voices()
                    .get(1)
                    .events()
                    .getFirst()
                    .event())
            .tieStart());
    assertFalse(
        ((Note)
                edited
                    .parts()
                    .getFirst()
                    .measures()
                    .get(1)
                    .voices()
                    .get(1)
                    .events()
                    .getFirst()
                    .event())
            .tieStop());
  }

  @Test
  void rejectsTiesWithoutAnAdjacentMatchingPitch() {
    var meter = new TimeSignature(4, 4);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Part(
                "P1",
                "Flute",
                List.of(
                    new Measure(
                        "1",
                        meter,
                        Optional.empty(),
                        List.of(
                            new Note(Pitch.fromMidiNumber(60), QUARTER, true, false),
                            new Note(Pitch.fromMidiNumber(62), QUARTER, false, true),
                            new Rest(new Duration(1, 2))),
                        false))));
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
