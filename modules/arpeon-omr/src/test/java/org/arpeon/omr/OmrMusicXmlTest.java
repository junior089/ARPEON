package org.arpeon.omr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.arpeon.musicxml.MusicXmlImporter;
import org.junit.jupiter.api.Test;

class OmrMusicXmlTest {
  static final String SIMPLE =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <!DOCTYPE score-partwise  PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
      <score-partwise version="4.0">
        <work><work-title>Reconhecida</work-title></work>
        <movement-title>Reconhecida</movement-title>
        <defaults><scaling><millimeters>7</millimeters><tenths>40</tenths></scaling></defaults>
        <part-list><score-part id="P1"><part-name default-x="8">Flauta</part-name>
          <score-instrument id="I1"><instrument-name>Flute</instrument-name></score-instrument>
          <midi-instrument id="I1"><midi-channel>1</midi-channel><midi-program>74</midi-program></midi-instrument>
        </score-part></part-list>
        <part id="P1"><measure number="1" width="100">
          <print><system-layout/></print>
          <attributes><divisions>480</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes>
          <note default-x="10"><pitch><step>C</step><octave>5</octave></pitch><duration>480</duration><type>quarter</type><stem>up</stem></note>
          <note><pitch><step>D</step><octave>5</octave></pitch><duration>480</duration><type>quarter</type></note>
          <note><pitch><step>E</step><octave>5</octave></pitch><duration>480</duration><type>quarter</type></note>
          <note><pitch><step>F</step><octave>5</octave></pitch><duration>480</duration><type>quarter</type></note>
          <barline location="right"><bar-style>light-light</bar-style></barline>
        </measure></part>
      </score-partwise>
      """;

  @Test
  void normalizesPresentationOnlyMetadataAndParsesRecognizedNotes() throws Exception {
    var normalized = OmrMusicXml.normalize(SIMPLE.getBytes(StandardCharsets.UTF_8));
    var imported =
        new MusicXmlImporter().read(new ByteArrayInputStream(normalized.xml()), "omr.xml");
    assertEquals("Reconhecida", imported.score().title());
    assertEquals("flute", imported.instruments().get("P1"));
    assertEquals(4, imported.score().parts().getFirst().measures().getFirst().events().size());
    assertTrue(normalized.warnings().stream().anyMatch(w -> w.contains("diagramação")));
  }

  @Test
  void omitsSlurWithWarningWithoutChangingNotesOrDurations() throws Exception {
    String withSlur =
        SIMPLE.replace(
            "<stem>up</stem>",
            "<stem>up</stem><notations><slur type=\"start\" number=\"1\"/></notations>");
    var normalized = OmrMusicXml.normalize(withSlur.getBytes(StandardCharsets.UTF_8));
    var imported =
        new MusicXmlImporter().read(new ByteArrayInputStream(normalized.xml()), "omr.xml");
    assertEquals(4, imported.score().parts().getFirst().measures().getFirst().events().size());
    assertTrue(normalized.unsupportedSymbols().contains("slur"));
  }

  @Test
  void omitsVisualRestPositionWithoutChangingTheRecognizedRest() throws Exception {
    String withPositionedRest =
        SIMPLE.replace(
            "<pitch><step>C</step><octave>5</octave></pitch>",
            "<rest><display-step>C</display-step><display-octave>5</display-octave></rest>");
    var normalized = OmrMusicXml.normalize(withPositionedRest.getBytes(StandardCharsets.UTF_8));
    var imported =
        new MusicXmlImporter().read(new ByteArrayInputStream(normalized.xml()), "omr.xml");
    assertEquals(4, imported.score().parts().getFirst().measures().getFirst().events().size());
    assertFalse(new String(normalized.xml(), StandardCharsets.UTF_8).contains("display-step"));
  }

  @Test
  void preservesRecognizedChordHeads() throws Exception {
    String firstNote =
        "<note default-x=\"10\"><pitch><step>C</step><octave>5</octave></pitch>"
            + "<duration>480</duration><type>quarter</type><stem>up</stem></note>";
    String chordHead =
        "<note><chord/><pitch><step>E</step><octave>5</octave></pitch>"
            + "<duration>480</duration><type>quarter</type></note>";
    var normalized =
        OmrMusicXml.normalize(
            SIMPLE.replace(firstNote, firstNote + chordHead).getBytes(StandardCharsets.UTF_8));
    var imported =
        new MusicXmlImporter().read(new ByteArrayInputStream(normalized.xml()), "omr.xml");
    assertEquals(4, imported.score().parts().getFirst().measures().getFirst().events().size());
    assertEquals(
        2,
        imported
            .score()
            .parts()
            .getFirst()
            .measures()
            .getFirst()
            .events()
            .getFirst()
            .noteHeads()
            .size());
  }

  @Test
  void preservesRecognizedIndependentVoicesAndCursorMovement() throws Exception {
    String multiVoice =
        SIMPLE.replace(
            "<barline location=\"right\">",
            "<backup><duration>1920</duration></backup>"
                + "<note><pitch><step>A</step><octave>4</octave></pitch><duration>480</duration>"
                + "<voice>2</voice><type>quarter</type></note>"
                + "<forward><duration>1440</duration></forward><barline location=\"right\">");
    var normalized = OmrMusicXml.normalize(multiVoice.getBytes(StandardCharsets.UTF_8));
    var imported =
        new MusicXmlImporter().read(new ByteArrayInputStream(normalized.xml()), "omr.xml");
    var measure = imported.score().parts().getFirst().measures().getFirst();
    assertEquals(2, measure.voices().size());
    assertEquals("2", measure.voices().get(1).id());
    assertTrue(normalized.xml().length > 0);
  }

  @Test
  void rejectsMalformedCursorMarkupAndEntityDefinitions() {
    assertThrows(
        IOException.class,
        () ->
            OmrMusicXml.normalize(
                SIMPLE
                    .replace(
                        "<type>quarter</type><stem>up</stem></note>",
                        "<type>quarter</type><backup><duration>480</duration></backup></note>")
                    .getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        IOException.class,
        () ->
            OmrMusicXml.normalize(
                SIMPLE
                    .replace("<score-partwise", "<!ENTITY x \"bad\"><score-partwise")
                    .getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void preservesRecognizedRepeatsFromOmrMusicXml() throws Exception {
    String repeated =
        SIMPLE.replace("<bar-style>light-light</bar-style>", "<repeat direction=\"backward\"/>");
    var normalized = OmrMusicXml.normalize(repeated.getBytes(StandardCharsets.UTF_8));
    var imported =
        new MusicXmlImporter().read(new ByteArrayInputStream(normalized.xml()), "omr.xml");

    assertEquals(1, imported.score().repeatRegions().size());
    assertEquals(2, imported.score().repeatRegions().getFirst().passes());
  }

  @Test
  void enforcesSharedXmlStructureBudgetBeforeDomNormalization() {
    String excessive = "<score-partwise>" + "<x/>".repeat(MusicXmlImporter.MAX_XML_ELEMENTS);
    assertThrows(
        IOException.class,
        () ->
            OmrMusicXml.normalize(
                (excessive + "</score-partwise>").getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsMalformedUtf8() {
    assertThrows(IOException.class, () -> OmrMusicXml.normalize(new byte[] {(byte) 0xc3, 0x28}));
  }

  @Test
  void preservesSoundAndVisualTiesFromRecognizedMusicXml() throws Exception {
    String withTies =
        SIMPLE
            .replace(
                "<stem>up</stem>",
                "<stem>up</stem><tie type=\"start\"/><notations><tied type=\"start\"/>"
                    + "</notations>")
            .replace(
                "<pitch><step>D</step><octave>5</octave></pitch><duration>480</duration>"
                    + "<type>quarter</type>",
                "<pitch><step>C</step><octave>5</octave></pitch><duration>480</duration>"
                    + "<tie type=\"stop\"/><type>quarter</type>"
                    + "<notations><tied type=\"stop\"/></notations>");
    var normalized = OmrMusicXml.normalize(withTies.getBytes(StandardCharsets.UTF_8));
    var score = new MusicXmlImporter().read(new ByteArrayInputStream(normalized.xml()), "omr.xml");
    var events = score.score().parts().getFirst().measures().getFirst().events();
    assertTrue(((org.arpeon.domain.score.Note) events.getFirst()).tieStart());
    assertTrue(((org.arpeon.domain.score.Note) events.get(1)).tieStop());
  }
}
