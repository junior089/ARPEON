package org.arpeon.musicxml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.time.Duration;
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
    assertThrows(IOException.class, () -> read(" ".repeat(MusicXmlImporter.MAX_BYTES + 1)));
    assertThrows(IOException.class, () -> read("<x>".repeat(40) + "</x>".repeat(40)));
    assertThrows(IOException.class, () -> read("PK not uncompressed XML"));
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
    assertThrows(
        IOException.class,
        () ->
            read(
                original.replace(
                    "<time>", "<transpose><chromatic>-2</chromatic></transpose><time>")));
    assertThrows(
        IOException.class, () -> read(original.replace("<note>", "<note><voice>2</voice>")));
    assertThrows(IOException.class, () -> read(original.replace("<divisions>4</divisions>", "")));
    assertThrows(
        IOException.class,
        () -> read(original.replaceFirst("<duration>4</duration>", "<duration>3</duration>")));
  }
}
