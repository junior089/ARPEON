package org.arpeon.musicxml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.Tempo;
import org.arpeon.domain.time.TimeSignature;
import org.junit.jupiter.api.Test;

class MusicXmlExporterTest {
  @Test
  void exportedEditedScoreRoundTripsWithKeyRhythmAndEscapedMetadata() throws Exception {
    Score original =
        new Score(
            "A&B <draft>",
            List.of(
                new Part(
                    "P1",
                    "Flute & clarinet",
                    List.of(
                        new Measure(
                            "1",
                            new TimeSignature(3, 4),
                            Optional.of(new Tempo(88)),
                            List.of(
                                new Note(
                                    new Pitch(PitchClass.D, Accidental.FLAT, 4),
                                    new Duration(3, 8)),
                                new Rest(new Duration(1, 8)),
                                new Note(
                                    new Pitch(PitchClass.E, Accidental.FLAT, 4),
                                    new Duration(1, 4))),
                            false)))));
    String xml = new MusicXmlExporter().write(original, "Db_MAJOR");
    assertTrue(xml.contains("<fifths>-5</fifths>"));
    assertTrue(xml.contains("A&amp;B &lt;draft&gt;"));
    var imported =
        new MusicXmlImporter()
            .read(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "edited.musicxml");
    assertEquals(original, imported.score());
    assertEquals("Db_MAJOR", imported.keyId());
  }

  @Test
  void rejectsInvalidOrChangingKeySignatures() {
    String xml =
        "<score-partwise><part-list><score-part"
            + " id=\"P1\"><part-name>One</part-name></score-part></part-list><part"
            + " id=\"P1\"><measure number=\"1\"><attributes><divisions>1</divisions>"
            + "<time><beats>1</beats><beat-type>4</beat-type></time>"
            + "<key><fifths>9</fifths></key></attributes>"
            + "<note><rest/><duration>1</duration></note></measure></part></score-partwise>";
    assertThrows(
        java.io.IOException.class,
        () ->
            new MusicXmlImporter()
                .read(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                    "bad.musicxml"));
  }

  @Test
  void transposingInstrumentRoundTripsItsProgramAndWrittenKey() throws Exception {
    Score score =
        new Score(
            "Clarinet",
            List.of(
                new Part(
                    "P1",
                    "B-flat clarinet",
                    List.of(
                        new Measure(
                            "1",
                            new TimeSignature(1, 4),
                            Optional.empty(),
                            List.of(new Note(Pitch.fromMidiNumber(62), new Duration(1, 4))),
                            false)))));
    String xml =
        new MusicXmlExporter()
            .write(score, "C_MAJOR", Map.of("P1", InstrumentCatalog.get("bb-clarinet")));
    assertTrue(xml.contains("<midi-program>72</midi-program>"));
    assertTrue(xml.contains("<fifths>2</fifths>"));
    assertTrue(xml.contains("<chromatic>-2</chromatic>"));
    var imported =
        new MusicXmlImporter()
            .read(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                "clarinet.musicxml");
    assertEquals(score, imported.score());
    assertEquals("C_MAJOR", imported.keyId());
    assertEquals("bb-clarinet", imported.instruments().get("P1"));
  }
}
