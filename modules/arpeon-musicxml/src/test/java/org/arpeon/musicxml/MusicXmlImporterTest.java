package org.arpeon.musicxml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.arpeon.domain.score.Chord;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.ScoreOffset;
import org.junit.jupiter.api.Test;

class MusicXmlImporterTest {
  private final MusicXmlImporter importer = new MusicXmlImporter();

  private String fixture() throws IOException {
    return Files.readString(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml"));
  }

  private MusicXmlImporter.ImportedScore read(String text) throws IOException {
    return importer.read(
        new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "test.musicxml");
  }

  @Test
  void importsRealFixtureAndRetainsProvenance() throws IOException {
    var result = read(fixture());
    assertEquals("First notes", result.score().title());
    var measures = result.score().parts().getFirst().measures();
    assertEquals(2, measures.size());
    assertEquals(72, measures.getFirst().tempo().orElseThrow().quarterNotesPerMinute());
    assertEquals("D#4", ((Note) measures.getFirst().events().get(1)).writtenPitch().label());
    assertEquals(new Duration(1, 2), ((Rest) measures.getFirst().events().get(2)).duration());
    assertEquals(64, result.sha256().length());
    assertEquals("test.musicxml", result.sourceFilename());
  }

  @Test
  void streamingAndByteArrayEntryPointsProduceTheSameImportAndDigest() throws IOException {
    byte[] xml = fixture().getBytes(StandardCharsets.UTF_8);
    var streamed = importer.read(new ByteArrayInputStream(xml), "test.musicxml");
    var inMemory = importer.read(xml, "test.musicxml");
    assertEquals(inMemory.score(), streamed.score());
    assertEquals(inMemory.sha256(), streamed.sha256());
  }

  @Test
  void acceptsValidLocalXmlBeyondTheFormerFifteenMiBCap() throws IOException {
    var result = read(fixture() + " ".repeat(16 * 1_048_576));
    assertEquals(2, result.score().parts().getFirst().measures().size());
  }

  @Test
  void importsAlignedPartsAndTempoChange() throws IOException {
    String xml =
        Files.readString(Path.of(System.getProperty("arpeon.fixtures"), "two-parts.musicxml"));
    var result = read(xml).score();
    assertEquals(2, result.parts().size());
    assertEquals("Accompaniment", result.parts().get(1).name());
    assertEquals(
        new org.arpeon.domain.time.TimeSignature(3, 4),
        result.parts().getFirst().measures().get(1).timeSignature());
    assertEquals(
        120,
        result.parts().getFirst().measures().get(1).tempo().orElseThrow().quarterNotesPerMinute());
  }

  @Test
  void importsExportsAndReopensAChordWithoutAddingItsDurationTwice() throws IOException {
    String singleNote =
        "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration>"
            + "<type>quarter</type></note>";
    String chordHead =
        "<note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration>"
            + "<type>quarter</type></note>";
    var imported = read(fixture().replace(singleNote, singleNote + chordHead));
    var firstEvent = imported.score().parts().getFirst().measures().getFirst().events().getFirst();
    assertInstanceOf(Chord.class, firstEvent);
    var chord = (Chord) firstEvent;
    assertEquals(
        List.of("C4", "E4"), chord.notes().stream().map(n -> n.writtenPitch().label()).toList());
    assertEquals(new Duration(1, 4), chord.duration());

    String exported = new MusicXmlExporter().write(imported.score());
    assertTrue(exported.contains("<note><chord/><pitch>"));
    assertEquals(imported.score(), read(exported).score());
  }

  @Test
  void importsIndependentVoicesAtTheirWrittenMeasureOffsets() throws IOException {
    String xml =
        "<score-partwise version=\"4.0\"><part-list><score-part"
            + " id=\"P1\"><part-name>Piano</part-name></score-part></part-list><part"
            + " id=\"P1\"><measure number=\"1\">"
            + "<attributes><divisions>4</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>"
            + "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice></note>"
            + "<backup><duration>4</duration></backup>"
            + "<note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><voice>2</voice></note>"
            + "<forward><duration>12</duration></forward></measure></part></score-partwise>";

    var imported = read(xml).score();
    var measure = imported.parts().getFirst().measures().getFirst();

    assertEquals(List.of("1", "2"), measure.voices().stream().map(voice -> voice.id()).toList());
    var event = measure.voices().get(1).events().getFirst();
    assertEquals(ScoreOffset.zero(), event.onset());
    assertEquals(new Duration(1, 4), event.event().duration());
    assertEquals("E4", ((Note) event.event()).writtenPitch().label());
    assertEquals(imported, read(new MusicXmlExporter().write(imported)).score());
  }

  @Test
  void preservesTrailingForwardInAnExplicitlyTimedSingleVoice() throws IOException {
    String xml =
        "<score-partwise version=\"4.0\"><part-list><score-part"
            + " id=\"P1\"><part-name>Voice</part-name></score-part></part-list><part"
            + " id=\"P1\"><measure number=\"1\">"
            + "<attributes><divisions>4</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>"
            + "<note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice></note>"
            + "<forward><duration>2</duration></forward></measure></part></score-partwise>";

    var imported = read(xml).score();
    var voice = imported.parts().getFirst().measures().getFirst().voices().getFirst();

    assertTrue(voice.explicitlyTimed());
    assertEquals(imported, read(new MusicXmlExporter().write(imported)).score());
  }

  @Test
  void importsAndExportsBoundedRepeatsWithPassSpecificEndings() throws IOException {
    String xml =
        "<score-partwise version=\"4.0\"><part-list><score-part"
            + " id=\"P1\"><part-name>Melody</part-name></score-part></part-list><part"
            + " id=\"P1\"><measure"
            + " number=\"1\"><attributes><divisions>4</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes><barline"
            + " location=\"left\"><repeat direction=\"forward\"/></barline>"
            + "<note><pitch><step>C</step><octave>4</octave></pitch><duration>16</duration></note></measure><measure"
            + " number=\"2\"><barline location=\"left\"><ending number=\"1\""
            + " type=\"start\"/></barline>"
            + "<note><pitch><step>D</step><octave>4</octave></pitch><duration>16</duration></note><barline"
            + " location=\"right\"><ending number=\"1\" type=\"stop\"/></barline></measure><measure"
            + " number=\"3\"><barline location=\"left\"><ending number=\"2\""
            + " type=\"start\"/></barline>"
            + "<note><pitch><step>E</step><octave>4</octave></pitch><duration>16</duration></note><barline"
            + " location=\"right\"><ending number=\"2\" type=\"stop\"/><repeat"
            + " direction=\"backward\" times=\"2\"/></barline></measure></part></score-partwise>";

    var imported = read(xml).score();
    var roundTrip = read(new MusicXmlExporter().write(imported)).score();

    assertEquals(3, imported.parts().getFirst().measures().size());
    assertEquals(imported, roundTrip);
    assertEquals(1, imported.repeatRegions().size());
    assertEquals(2, imported.endingRegions().size());
  }

  @Test
  void preservesCrossMeasureTiesThroughMusicXmlExportAndReimport() throws IOException {
    String xml =
        fixture()
            .replace(
                "<note><rest/><duration>8</duration><type>half</type></note>",
                "<note><pitch><step>C</step><octave>4</octave></pitch><duration>8</duration>"
                    + "<tie type=\"start\"/><type>half</type><notations><tied type=\"start\"/>"
                    + "</notations></note>")
            .replace(
                "<note><pitch><step>A</step><octave>4</octave></pitch><duration>16</duration>"
                    + "<type>whole</type></note>",
                "<note><pitch><step>C</step><octave>4</octave></pitch><duration>16</duration>"
                    + "<tie type=\"stop\"/><type>whole</type><notations><tied type=\"stop\"/>"
                    + "</notations></note>");
    var imported = read(xml);
    var first = (Note) imported.score().parts().getFirst().measures().getFirst().events().get(2);
    var second = (Note) imported.score().parts().getFirst().measures().get(1).events().getFirst();
    assertTrue(first.tieStart());
    assertTrue(second.tieStop());

    String exported = new MusicXmlExporter().write(imported.score());
    var reopened = read(exported).score().parts().getFirst();
    assertTrue(((Note) reopened.measures().getFirst().events().get(2)).tieStart());
    assertTrue(((Note) reopened.measures().get(1).events().getFirst()).tieStop());
  }

  @Test
  void blocksDoctypeAndExternalEntities() {
    String xml =
        "<!DOCTYPE score-partwise [<!ENTITY xxe SYSTEM"
            + " 'file:///arpeon-test-secret'>]><score-partwise>&xxe;</score-partwise>";
    assertThrows(IOException.class, () -> read(xml));
    assertThrows(
        IOException.class,
        () ->
            read(
                "<!DOCTYPE score-partwise SYSTEM"
                    + " 'https://example.invalid/score.dtd'><score-partwise/>"));
  }

  @Test
  void rejectsOversizedDeepAndNonXmlInputs() {
    assertThrows(
        IOException.class,
        () -> new MusicXmlImporter().read(new byte[MusicXmlImporter.MAX_BYTES + 1], "large.xml"));
    assertThrows(IOException.class, () -> read("<x>".repeat(40) + "</x>".repeat(40)));
    assertThrows(IOException.class, () -> read("PK not uncompressed XML"));
  }

  @Test
  void rejectsExcessiveXmlElementCountBeforeBuildingTheScore() {
    String xml = "<score-partwise>" + "<x/>".repeat(MusicXmlImporter.MAX_XML_ELEMENTS);
    assertThrows(IOException.class, () -> read(xml + "</score-partwise>"));
  }

  @Test
  void readsMxlWhileDrainingUnneededEntriesWithoutKeepingThemInMemory() throws IOException {
    byte[] archive;
    try (var output = new ByteArrayOutputStream();
        var zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
      zip.write(
          ("<container><rootfiles><rootfile full-path=\"score.xml\""
                  + " media-type=\"application/vnd.recordare.musicxml+xml\"/>"
                  + "</rootfiles></container>")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("extras/unused.bin"));
      zip.write(new byte[64 * 1024]);
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("score.xml"));
      zip.write(fixture().getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      archive = output.toByteArray();
    }

    var imported = importer.readMxl(archive, "packed.mxl");
    assertEquals("packed.mxl", imported.sourceFilename());
    assertTrue(imported.parserVersion().startsWith("mxl-v1+"));
    assertEquals("First notes", imported.score().title());
  }

  @Test
  void acceptsPublicDtdMxlAndMuseScoreMsczSamples() throws IOException {
    Path fixtures = Path.of(System.getProperty("arpeon.fixtures"));
    byte[] mxl = Files.readAllBytes(fixtures.resolve("badinerie-for-flute-by-js-bach.mxl"));
    byte[] mscz = Files.readAllBytes(fixtures.resolve("badinerie-for-flute-by-js-bach.mscz"));

    var compressedMusicXml = importer.readMxl(mxl, "badinerie.mxl");
    var museScore = importer.readMxl(mscz, "badinerie.mscz");

    assertEquals(42, compressedMusicXml.score().parts().getFirst().measures().size());
    assertEquals(42, museScore.score().parts().getFirst().measures().size());
    assertEquals("Badinerie", museScore.score().title());
    assertEquals(1, museScore.score().repeatRegions().size());
    var scoreNotes =
        museScore.score().parts().getFirst().measures().stream()
            .flatMap(measure -> measure.events().stream())
            .filter(Note.class::isInstance)
            .map(Note.class::cast)
            .toList();
    assertTrue(scoreNotes.size() > 200);
    assertTrue(scoreNotes.stream().anyMatch(Note::tieStart));
    assertTrue(scoreNotes.stream().anyMatch(Note::tieStop));
  }

  @Test
  void neverSilentlyDropsUnsupportedMusic() throws IOException {
    String original = fixture();
    assertThrows(
        IOException.class,
        () -> read(original.replace("<type>quarter</type>", "<type>half</type>")));
    assertThrows(
        IOException.class,
        () -> read(original.replace("<type>quarter</type>", "<type>quarter</type><dot/>")));
    assertThrows(
        IOException.class, () -> read(original.replace("<note>", "<note><tie type=\"start\"/>")));
    assertThrows(IOException.class, () -> read(original.replace("<note>", "<note><chord/>")));
    var transposed =
        read(original.replace("<time>", "<transpose><chromatic>-2</chromatic></transpose><time>"));
    assertEquals(
        -2,
        org.arpeon.domain.instrument.InstrumentCatalog.get(transposed.instruments().get("P1"))
            .soundingTranspositionSemitones());
    assertDoesNotThrow(() -> read(original.replace("<note>", "<note><voice>2</voice>")));
    assertThrows(IOException.class, () -> read(original.replace("<divisions>4</divisions>", "")));
    assertThrows(
        IOException.class,
        () -> read(original.replaceFirst("<duration>4</duration>", "<duration>3</duration>")));
  }
}
