package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.arpeon.analysis.AnalysisConfig;
import org.arpeon.analysis.ObservedEvent;
import org.arpeon.analysis.PerformanceAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisStudyTest {
  @TempDir Path temporary;
  private static final AnalysisConfig CONFIG = new AnalysisConfig(450, 120, 50, 20, 0);

  @Test
  void mapsRepeatedPassagesToWrittenEventsAndPreparesWithoutStartingAudio() throws Exception {
    try (var workspace = new ScoreWorkspace()) {
      workspace.openMxl(
          Files.readAllBytes(
              Path.of(System.getProperty("arpeon.fixtures"), "badinerie-for-flute-by-js-bach.mxl")),
          "bach.mxl");
      var source = workspace.analysisSource("P1");
      assertEquals(source.expected().size(), source.locations().size());
      var repeated =
          source.locations().stream()
              .filter(location -> location.executionMeasureIndex() > location.measureIndex())
              .findFirst()
              .orElseThrow();
      var page = workspace.measureEvents("P1", repeated.measureIndex());
      assertEquals(page.number(), repeated.measureNumber());
      assertEquals("NOTE", page.events().get(repeated.eventIndex()).kind());
      var before = workspace.exportXml();
      var prepared = workspace.prepareStudy(request(source, repeated.index(), true));
      assertNotEquals("PLAYING", prepared.transport().status());
      assertEquals(60, prepared.transport().startingBpm());
      assertEquals(
          repeated.executionMeasureIndex(), prepared.transport().loop().startMeasureIndex());
      assertTrue(prepared.transport().loop().endMeasureIndex() >= repeated.endMeasureIndex());
      assertEquals(prepared.transport().loop().startTick(), prepared.transport().tick());
      var viewed = workspace.prepareStudy(request(source, repeated.index(), false));
      assertNull(viewed.transport().loop());
      assertEquals(
          source.expectedTicks().get(repeated.index()).longValue(), viewed.transport().tick());
      assertEquals(before, workspace.exportXml());
      assertEquals(0, workspace.snapshot().score().revision());
    }
  }

  @Test
  void validatesEveryStudyParameterBeforeChangingTransport() throws Exception {
    try (var workspace = firstNotes()) {
      var source = workspace.analysisSource("P1");
      var before = workspace.transport();
      for (String field : List.of("bpm", "expectedIndex", "revision", "soundingSemitones")) {
        var request = request(source, 0, true);
        request.addProperty(field, field.equals("bpm") ? 10 : -1);
        assertThrows(RuntimeException.class, () -> workspace.prepareStudy(request));
        assertEquals(before, workspace.transport());
      }
      var wrongXml = request(source, 0, true);
      wrongXml.addProperty("scoreXmlSha256", "f".repeat(64));
      assertThrows(IllegalStateException.class, () -> workspace.prepareStudy(wrongXml));
      var wrongSource = request(source, 0, true);
      wrongSource.addProperty("sha256", "f".repeat(64));
      assertThrows(IllegalStateException.class, () -> workspace.prepareStudy(wrongSource));
      var invalid = request(source, 0, true);
      invalid.addProperty("repeat", "true");
      assertThrows(IllegalArgumentException.class, () -> workspace.prepareStudy(invalid));
      assertEquals(before, workspace.transport());
    }
  }

  @Test
  void includesTiedContinuationsInLoopAndRejectsChangedXmlAfterProjectReload() throws Exception {
    String xml =
        """
        <score-partwise version="4.0"><part-list><score-part id="P1"><part-name>Study</part-name></score-part></part-list><part id="P1">
        <measure number="A"><attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes>
        <note><rest/><duration>1</duration><type>quarter</type></note><note><pitch><step>C</step><octave>4</octave></pitch><duration>3</duration><tie type="start"/><type>half</type><dot/></note></measure>
        <measure number="B"><note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><tie type="stop"/><type>whole</type></note></measure>
        </part></score-partwise>
        """;
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), "ties.musicxml");
      var source = workspace.analysisSource("P1");
      assertEquals(1, source.locations().size());
      assertEquals(1, source.locations().getFirst().eventIndex());
      assertEquals(1, source.locations().getFirst().endMeasureIndex());
      assertEquals(
          1, workspace.prepareStudy(request(source, 0, true)).transport().loop().endMeasureIndex());
      var data = workspace.projectData();
      var changed =
          new ProjectStore.Data(
              data.title(),
              data.filename(),
              data.sha256(),
              data.parserVersion(),
              data.sourceKind(),
              data.importWarnings(),
              data.source(),
              data.xml().replace("<step>C</step>", "<step>D</step>"));
      workspace.openProject(changed);
      assertEquals(0, workspace.snapshot().score().revision());
      assertEquals(source.sha256(), workspace.snapshot().score().sha256());
      assertThrows(
          IllegalStateException.class, () -> workspace.prepareStudy(request(source, 0, true)));
    }
  }

  @Test
  void historyPreservesCriteriaAndLocationsAndReadsLegacyEntries() throws Exception {
    try (var workspace = firstNotes()) {
      var source = workspace.analysisSource("P1");
      var observed =
          source.expected().stream()
              .map(
                  note ->
                      new ObservedEvent(
                          note.index(), note.midi(), note.onsetMs(), note.durationMs(), 0, .95, 3))
              .toList();
      var report = PerformanceAnalyzer.analyze(source.expected(), observed, CONFIG);
      var context =
          new AnalysisStudy.Context(
              source.scoreXmlSha256(),
              source.initialBpm(),
              source.soundingSemitones(),
              CONFIG,
              source.locations());
      var entry =
          new AnalysisHistory.Entry(
              UUID.randomUUID().toString(),
              null,
              source.sha256(),
              0,
              source.title(),
              source.filename(),
              source.partId(),
              source.partName(),
              "ANALYSIS",
              "2026-10-11T03:00:00Z",
              report,
              context);
      var file = temporary.resolve("history.json");
      new AnalysisHistory(file).add(entry);
      assertEquals(entry, new AnalysisHistory(file).get(entry.id()));
      var json = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
      json.get(0).getAsJsonObject().remove("study");
      Files.writeString(file, new Gson().toJson(json));
      assertNull(new AnalysisHistory(file).get(entry.id()).study());
      assertEquals(report, new AnalysisHistory(file).get(entry.id()).report());
      json.get(0).getAsJsonObject().add("study", new Gson().toJsonTree(context));
      json.get(0)
          .getAsJsonObject()
          .getAsJsonObject("study")
          .getAsJsonArray("notes")
          .get(0)
          .getAsJsonObject()
          .addProperty("index", 2);
      Files.writeString(file, new Gson().toJson(json));
      assertThrows(IOException.class, () -> new AnalysisHistory(file).summaries());
    }
  }

  private static ScoreWorkspace firstNotes() throws Exception {
    var workspace = new ScoreWorkspace();
    workspace.open(
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml")),
        "first.musicxml");
    return workspace;
  }

  private static JsonObject request(
      ScoreWorkspace.AnalysisSource source, int index, boolean repeat) {
    var request = new JsonObject();
    request.addProperty("sha256", source.sha256());
    request.addProperty("revision", source.revision());
    request.addProperty("scoreXmlSha256", source.scoreXmlSha256());
    request.addProperty("partId", source.partId());
    request.addProperty("soundingSemitones", source.soundingSemitones());
    request.addProperty("expectedIndex", index);
    request.addProperty("bpm", 60);
    request.addProperty("repeat", repeat);
    return request;
  }
}
