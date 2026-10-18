package org.arpeon.musicxml;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.score.Clef;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.score.ScoreDocument;
import org.arpeon.domain.score.Tuplet;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.TimeSignature;
import org.junit.jupiter.api.Test;

class EnsembleRoundTripTest {
  @Test
  void preservesDistinctInstrumentsTupletsBassClefAndComposerAcrossExportAndEdits()
      throws Exception {
    var triplet = new Duration(1, 12);
    var melody =
        new Measure(
            "1",
            new TimeSignature(4, 4),
            Optional.empty(),
            List.of(
                new Note(
                    Pitch.fromMidiNumber(60), triplet, false, false, new Tuplet(3, 2, true, false)),
                new Note(
                    Pitch.fromMidiNumber(62),
                    triplet,
                    false,
                    false,
                    new Tuplet(3, 2, false, false)),
                new Note(
                    Pitch.fromMidiNumber(64), triplet, false, false, new Tuplet(3, 2, false, true)),
                new Rest(new Duration(3, 4))),
            false);
    var bass =
        new Measure(
            "1",
            new TimeSignature(4, 4),
            Optional.empty(),
            List.of(new Note(Pitch.fromMidiNumber(48), new Duration(1, 1))),
            false);
    var score =
        new Score(
            "Synthetic trio",
            List.of(
                new Part("P1", "Flute", List.of(melody)),
                new Part("P2", "Cello", List.of(bass), Map.of(0, new Clef("F", 4, 0)))),
            List.of(),
            List.of(),
            "Test composer");
    var profiles =
        Map.of("P1", InstrumentCatalog.get("flute"), "P2", InstrumentCatalog.get("cello"));
    var xml = new MusicXmlExporter().write(score, null, profiles);
    var imported =
        new MusicXmlImporter().read(xml.getBytes(StandardCharsets.UTF_8), "ensemble.musicxml");
    assertEquals(score, imported.score());
    assertEquals("cello", imported.instruments().get("P2"));
    var changed = ScoreDocument.setPitch(imported.score(), "P1", 0, 0, Pitch.fromMidiNumber(65));
    assertEquals(
        new Tuplet(3, 2, true, false),
        changed.parts().getFirst().measures().getFirst().events().getFirst().tuplet());
    assertEquals(new Clef("F", 4, 0), changed.parts().get(1).clefs().get(0));
    assertEquals("Test composer", changed.composer());
    assertEquals(
        changed,
        new MusicXmlImporter()
            .read(
                new MusicXmlExporter()
                    .write(changed, null, profiles)
                    .getBytes(StandardCharsets.UTF_8),
                "edited.musicxml")
            .score());
  }

  @Test
  void recognizesGeneralMidiProgramsAndPreservesWrittenTransposition() throws Exception {
    for (String id :
        List.of(
            "piccolo",
            "bb-clarinet",
            "alto-sax",
            "trumpet",
            "violin",
            "viola",
            "cello",
            "double-bass",
            "piano",
            "harpsichord",
            "guitar",
            "electric-guitar",
            "electric-bass",
            "gm-125")) {
      var profile = InstrumentCatalog.get(id);
      var measure =
          new Measure(
              "1",
              new TimeSignature(4, 4),
              Optional.empty(),
              List.of(new Note(Pitch.fromMidiNumber(60), new Duration(1, 1))),
              false);
      var score = new Score("Programs", List.of(new Part("P1", id, List.of(measure))));
      var imported =
          new MusicXmlImporter()
              .read(
                  new MusicXmlExporter()
                      .write(score, null, Map.of("P1", profile))
                      .getBytes(StandardCharsets.UTF_8),
                  "program.musicxml");
      var restored = InstrumentCatalog.get(imported.instruments().get("P1"));
      assertEquals(profile.midiProgram(), restored.midiProgram(), id);
      assertEquals(
          profile.soundingTranspositionSemitones(), restored.soundingTranspositionSemitones(), id);
      assertEquals(score, imported.score(), id);
    }
  }
}
