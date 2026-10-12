package org.arpeon.omr;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.TimeSignature;
import org.arpeon.musicxml.MusicXmlExporter;
import org.arpeon.musicxml.MusicXmlImporter;
import org.arpeon.transport.ScoreTimeline;
import org.junit.jupiter.api.Test;

class OmrRhythmDraftTest {
  private static final String ATTRIBUTES =
      "<attributes><divisions>480</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>";
  private static final String QUARTER =
      "<note><pitch><step>C</step><octave>5</octave></pitch><duration>480</duration><type>quarter</type></note>";

  private static String parts(String first, String second) {
    return "<score-partwise version=\"4.0\"><part-list><score-part"
        + " id=\"P1\"><part-name>One</part-name></score-part><score-part"
        + " id=\"P2\"><part-name>Two</part-name></score-part></part-list><part id=\"P1\">"
        + first
        + "</part><part id=\"P2\">"
        + second
        + "</part></score-partwise>";
  }

  @Test
  void consecutiveExcessWithLocalMeterDeclarationsKeepsBothProvisionalMeters() throws Exception {
    var draft =
        normalize(
            OmrMusicXmlTest.SIMPLE.replace(
                "</measure></part>",
                QUARTER
                    + "</measure><measure number=\"2\">"
                    + ATTRIBUTES
                    + QUARTER.repeat(5)
                    + "</measure></part>"));
    var score = new MusicXmlImporter().read(draft.xml(), "draft.xml").score();
    assertEquals(4800, new ScoreTimeline(score).totalTicks());
    for (var part : score.parts())
      for (var bar : part.measures()) assertEquals(new TimeSignature(5, 4), bar.timeSignature());
  }

  @Test
  void sharedProvisionalMeterPreservesNotesAndOpensAnExportableTimeline() throws Exception {
    String first =
        "<measure number=\"1\">"
            + ATTRIBUTES
            + QUARTER.repeat(4)
            + "</measure><measure number=\"2\">"
            + QUARTER.repeat(4)
            + "</measure>";
    String second =
        first.replace(
            QUARTER.repeat(4) + "</measure><measure", QUARTER.repeat(5) + "</measure><measure");
    var draft = normalize(parts(first, second));
    var score = new MusicXmlImporter().read(draft.xml(), "draft.xml").score();
    assertEquals(4, score.parts().getFirst().measures().getFirst().events().size());
    assertEquals(5, score.parts().getLast().measures().getFirst().events().size());
    for (var part : score.parts()) {
      assertEquals(new TimeSignature(5, 4), part.measures().getFirst().timeSignature());
      assertEquals(new TimeSignature(4, 4), part.measures().getLast().timeSignature());
    }
    var timeline = new ScoreTimeline(score);
    var exported = new MusicXmlExporter().write(score).getBytes(StandardCharsets.UTF_8);
    assertEquals(
        timeline.totalTicks(),
        new ScoreTimeline(new MusicXmlImporter().read(exported, "export.xml").score())
            .totalTicks());
    assertTrue(
        draft.warnings().stream()
            .anyMatch(w -> w.contains("P1 · compasso 1: alinhamento provisório")));
  }

  @Test
  void InconsistentPickupSpansAreDisclosedAndAlignedWithoutNewNotes() throws Exception {
    String first =
        "<measure number=\"0\" implicit=\"yes\">" + ATTRIBUTES + QUARTER.repeat(2) + "</measure>";
    String second = "<measure number=\"0\" implicit=\"yes\">" + ATTRIBUTES + QUARTER + "</measure>";
    var draft = normalize(parts(first, second));
    var score = new MusicXmlImporter().read(draft.xml(), "draft.xml").score();
    assertFalse(score.parts().getFirst().measures().getFirst().pickup());
    assertEquals(2, score.parts().getFirst().measures().getFirst().events().size());
    assertEquals(1, score.parts().getLast().measures().getFirst().events().size());
    assertEquals(1920, new ScoreTimeline(score).totalTicks());
    assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("anacruse")));
  }

  private static OmrMusicXml.Normalized normalize(String xml) throws Exception {
    return OmrMusicXml.normalize(xml.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void incompleteMeasureKeepsRecognizedNotesAndTheirDurations() throws Exception {
    var xml =
        OmrMusicXmlTest.SIMPLE.replace(
            "<pitch><step>F</step><octave>5</octave></pitch><duration>480</duration><type>quarter</type>",
            "<pitch><step>F</step><octave>5</octave></pitch><duration>240</duration><type>eighth</type>");
    assertThrows(
        java.io.IOException.class,
        () -> new MusicXmlImporter().read(xml.getBytes(StandardCharsets.UTF_8), "strict.xml"));
    var draft = normalize(xml);
    var measure =
        new MusicXmlImporter()
            .read(draft.xml(), "draft.xml")
            .score()
            .parts()
            .getFirst()
            .measures()
            .getFirst();
    assertEquals(4, measure.events().size());
    assertEquals(new Duration(1, 8), measure.events().getLast().duration());
    assertEquals(new TimeSignature(4, 4), measure.timeSignature());
    assertTrue(measure.voices().getFirst().explicitlyTimed());
    assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("compasso incompleto")));
  }

  @Test
  void excessGetsDisclosedProvisionalMeterThenOriginalMeterReturns() throws Exception {
    String note =
        "<note><pitch><step>G</step><octave>5</octave></pitch><duration>480</duration><type>quarter</type></note>";
    String xml =
        OmrMusicXmlTest.SIMPLE.replace(
            "</measure></part>",
            note
                + "</measure><measure number=\"2\">"
                + note.repeat(4)
                + "</measure><measure number=\"3\">"
                + note.repeat(4)
                + "</measure></part>");
    var draft = normalize(xml);
    var measures =
        new MusicXmlImporter().read(draft.xml(), "draft.xml").score().parts().getFirst().measures();
    assertEquals(new TimeSignature(5, 4), measures.get(0).timeSignature());
    assertEquals(new TimeSignature(4, 4), measures.get(1).timeSignature());
    assertEquals(new TimeSignature(4, 4), measures.get(2).timeSignature());
    assertEquals(5, measures.getFirst().events().size());
    assertTrue(
        measures.getFirst().events().stream()
            .allMatch(e -> e.duration().equals(new Duration(1, 4))));
    assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("Fórmula provisória 5/4")));
  }

  @Test
  void emptyMeasureUsesExplicitlyDisclosedPlaceholderWithoutInventingPitch() throws Exception {
    var xml =
        OmrMusicXmlTest.SIMPLE.replace(
            "</measure></part>", "</measure><measure number=\"2\"/></part>");
    var draft = normalize(xml);
    var measure =
        new MusicXmlImporter()
            .read(draft.xml(), "draft.xml")
            .score()
            .parts()
            .getFirst()
            .measures()
            .getLast();
    assertTrue(measure.events().getFirst().noteHeads().isEmpty());
    assertEquals(new Duration(1, 1), measure.events().getFirst().duration());
    assertTrue(
        draft.warnings().stream().anyMatch(w -> w.contains("não foi reconhecida no original")));
  }

  @Test
  void repeatedKeyIsHarmlessButActualKeyChangeRemainsRejected() throws Exception {
    String note = "<note><rest/><duration>1920</duration><type>whole</type></note>";
    var xml =
        OmrMusicXmlTest.SIMPLE.replace(
            "</measure></part>",
            "</measure><measure number=\"2\"><attributes><key><fifths>0</fifths></key></attributes>"
                + note
                + "</measure></part>");
    var draft = normalize(xml);
    assertEquals(
        2,
        new MusicXmlImporter()
            .read(draft.xml(), "draft.xml")
            .score()
            .parts()
            .getFirst()
            .measures()
            .size());
    assertThrows(
        java.io.IOException.class,
        () ->
            normalize(
                xml.replace(
                    "<key><fifths>0</fifths></key></attributes>" + note,
                    "<key><fifths>2</fifths></key></attributes>" + note)));
  }

  @Test
  void tupletActualDurationsAndPitchSurviveWithoutVisualGrouping() throws Exception {
    var xml =
        OmrMusicXmlTest.SIMPLE.replace(
            "<duration>480</duration><type>quarter</type><stem>up</stem>",
            "<duration>320</duration><type>quarter</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification><notations><tuplet"
                + " type=\"start\"/></notations>");
    var draft = normalize(xml);
    var event =
        new MusicXmlImporter()
            .read(draft.xml(), "draft.xml")
            .score()
            .parts()
            .getFirst()
            .measures()
            .getFirst()
            .events()
            .getFirst();
    assertEquals(new Duration(1, 6), event.duration());
    assertEquals("C5", event.noteHeads().getFirst().writtenPitch().label());
    assertTrue(draft.unsupportedSymbols().contains("tuplet"));
    assertTrue(
        draft.warnings().stream().anyMatch(w -> w.contains("durações reconhecidas preservadas")));
    assertThrows(
        java.io.IOException.class,
        () -> normalize(xml.replace("<actual-notes>3", "<actual-notes>0")));
  }

  @Test
  void malformedCursorIsRejectedInsteadOfRepairingNotes() {
    assertThrows(
        java.io.IOException.class,
        () ->
            normalize(
                OmrMusicXmlTest.SIMPLE.replace(
                    "</measure></part>",
                    "<backup><duration>3000</duration></backup></measure></part>")));
  }
}
