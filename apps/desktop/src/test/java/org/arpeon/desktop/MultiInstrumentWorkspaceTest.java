package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.TimeSignature;
import org.arpeon.musicxml.MusicXmlExporter;
import org.arpeon.musicxml.MusicXmlImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiInstrumentWorkspaceTest {
  @TempDir Path directory;

  private byte[] xml(int count, String instrument) {
    var measures = new java.util.ArrayList<Measure>();
    for (int i = 0; i < count; i++)
      measures.add(
          new Measure(
              "" + (i + 1),
              new TimeSignature(4, 4),
              Optional.empty(),
              List.of(new Note(Pitch.fromMidiNumber(67), new Duration(1, 1))),
              false));
    return new MusicXmlExporter()
        .write(
            new Score("Study", List.of(new Part("P1", instrument, measures))),
            null,
            Map.of("P1", InstrumentCatalog.get(instrument)))
        .getBytes(StandardCharsets.UTF_8);
  }

  private JsonObject json(String source) {
    return JsonParser.parseString(source).getAsJsonObject();
  }

  private JsonObject selection(
      ScoreWorkspace.PartImportView review, String replacement, boolean manual, int start) {
    var request = new JsonObject();
    request.addProperty("token", review.token());
    request.addProperty("partIds", "[\"P1\"]");
    request.addProperty("replaceId", replacement);
    request.addProperty("manual", manual);
    request.addProperty("anchors", "[{\"sourceMeasure\":0,\"compositionMeasure\":" + start + "}]");
    return request;
  }

  @Test
  void olderVersionTwoSettingsDefaultNewLibrariesAndSoundBankSafely() throws Exception {
    var store = new ProjectStore(directory);
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(xml(1, "flute"), "old.musicxml");
      var saved = store.save(null, workspace.projectData());
      Path path = directory.resolve(saved.id()).resolve("manifest.json");
      var manifest = json(Files.readString(path));
      var settings = manifest.getAsJsonObject("rehearsal").getAsJsonObject("playback");
      for (String field : List.of("mixPresets", "markers", "tempoPractice", "soundBankId"))
        settings.remove(field);
      Files.writeString(path, manifest.toString());
      var restored = workspace.openProject(store.load(saved.id()).data()).transport();
      assertTrue(restored.mixPresets().isEmpty());
      assertTrue(restored.markers().isEmpty());
      assertFalse(restored.tempoPractice().enabled());
      assertEquals("system", restored.soundBankId());
    }
  }

  @Test
  void previewsAlignmentWithoutCommittingAndPersistsNamedMixesAndStudyRanges() throws Exception {
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(xml(3, "flute"), "flute.musicxml");
      var review = workspace.stageParts(xml(2, "violin"), "late.musicxml", false);
      var request = selection(review, "", false, 0);
      request.remove("replaceId");
      assertFalse(workspace.previewPartImport(request).valid());
      request.addProperty("manual", true);
      request.addProperty("anchors", "[{\"sourceMeasure\":0,\"compositionMeasure\":1}]");
      var preview = workspace.previewPartImport(request);
      assertTrue(preview.valid());
      assertEquals(2, preview.parts().getFirst().rows().getFirst().targetPosition());
      assertEquals("4/4", preview.parts().getFirst().rows().getFirst().targetMeter());
      assertEquals(1, workspace.snapshot().score().parts().size());
      workspace.command("volume", json("{\"id\":\"P1\",\"value\":0.3}"));
      workspace.command("save-mix", json("{\"id\":\"\",\"name\":\"Cordas\"}"));
      workspace.command(
          "save-marker",
          json("{\"name\":\"Entrada\",\"startMeasureIndex\":1,\"endMeasureIndex\":2}"));
      workspace.command(
          "loop", json("{\"startMeasureIndex\":1,\"endMeasureIndex\":2,\"enabled\":true}"));
      workspace.command(
          "tempo-practice",
          json("{\"enabled\":true,\"stepBpm\":5,\"targetBpm\":180,\"loopsPerStep\":2}"));
      var store = new ProjectStore(directory);
      var saved = store.save(null, workspace.projectData());
      try (var restored = new ScoreWorkspace()) {
        restored.openProject(store.load(saved.id()).data());
        assertEquals("Cordas", restored.transport().mixPresets().getFirst().name());
        assertEquals(.3, restored.transport().parts().getFirst().volume());
        assertEquals("Entrada", restored.transport().markers().getFirst().name());
        assertTrue(restored.transport().tempoPractice().enabled());
      }
    }
  }

  @Test
  void importsSeparatePartsKeepsVisualSelectionIndependentAndPersistsMixer() throws Exception {
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(xml(3, "flute"), "flute.musicxml");
      var review = workspace.stageParts(xml(3, "violin"), "violin.musicxml", false);
      assertTrue(review.parts().getFirst().compatible());
      var view = workspace.edit("commit-import", selection(review, "", false, 0));
      assertEquals(2, view.score().parts().size());
      String violin = view.score().parts().get(1).id();
      workspace.command("select-part", json("{\"id\":\"P1\"}"));
      workspace.command("volume", json("{\"id\":\"" + violin + "\",\"value\":0.4}"));
      workspace.command("master", json("{\"value\":0.5}"));
      workspace.command("solo", json("{\"id\":\"" + violin + "\",\"enabled\":true}"));
      assertEquals("P1", workspace.transport().activePartId());
      assertEquals(0, workspace.transport().parts().getFirst().effectiveGain());
      assertEquals(.2, workspace.transport().parts().get(1).effectiveGain(), 1e-9);
      workspace.edit("view-mode", json("{\"mode\":\"individual\"}"));
      var store = new ProjectStore(directory);
      var saved = store.save(null, workspace.projectData());
      try (var reopened = new ScoreWorkspace()) {
        var restored = reopened.openProject(store.load(saved.id()).data());
        assertEquals(2, restored.score().parts().size());
        assertEquals(.4, restored.transport().parts().get(1).volume());
        assertEquals(.5, restored.transport().master());
        assertEquals("P1", restored.transport().activePartId());
        assertEquals("individual", restored.rehearsal().viewMode());
        assertEquals("violin.musicxml", restored.rehearsal().origins().get(violin).filename());
        assertEquals(
            1,
            new MusicXmlImporter()
                .read(reopened.exportPart(violin).getBytes(StandardCharsets.UTF_8), "part.musicxml")
                .score()
                .parts()
                .size());
        assertEquals(
            2,
            new MusicXmlImporter()
                .read(reopened.exportXml().getBytes(StandardCharsets.UTF_8), "all.musicxml")
                .score()
                .parts()
                .size());
      }
    }
  }

  @Test
  void reviewsAmbiguousImportsAndSupportsReplacementOrderRenameRemovalAndUndo() throws Exception {
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(xml(3, "flute"), "flute.musicxml");
      var review = workspace.stageParts(xml(2, "violin"), "late.musicxml", false);
      assertFalse(review.parts().getFirst().compatible());
      assertThrows(
          IllegalArgumentException.class,
          () -> workspace.edit("commit-import", selection(review, "", false, 0)));
      assertEquals(1, workspace.snapshot().score().parts().size());
      var added = workspace.edit("commit-import", selection(review, "", true, 1));
      String id = added.score().parts().get(1).id();
      workspace.edit("rename-part", json("{\"partId\":\"" + id + "\",\"name\":\"Violino I\"}"));
      var moved = workspace.edit("move-part", json("{\"partId\":\"" + id + "\",\"index\":0}"));
      assertEquals("Violino I", moved.score().parts().getFirst().name());
      var replacement = workspace.stageParts(xml(3, "cello"), "cello.musicxml", false);
      var replaced = workspace.edit("commit-import", selection(replacement, id, false, 0));
      assertEquals(2, replaced.score().parts().size());
      assertEquals(id, replaced.score().parts().getFirst().id());
      workspace.command("select-part", json("{\"id\":\"" + id + "\"}"));
      var removed = workspace.edit("remove-part", json("{\"partId\":\"" + id + "\"}"));
      assertEquals("P1", removed.transport().activePartId());
      assertEquals(2, workspace.edit("undo", new JsonObject()).score().parts().size());
    }
  }

  @Test
  void upgradesVersionOneManifestAndRejectsUnknownFutureVersions() throws Exception {
    var store = new ProjectStore(directory);
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(xml(1, "flute"), "flute.musicxml");
      var data = workspace.projectData();
      var old =
          new ProjectStore.Data(
              data.title(),
              data.filename(),
              data.sha256(),
              data.parserVersion(),
              data.sourceKind(),
              data.importWarnings(),
              data.source(),
              data.xml());
      var saved = store.save(null, old);
      var manifest = directory.resolve(saved.id()).resolve("manifest.json");
      var content = json(Files.readString(manifest));
      content.addProperty("schemaVersion", 1);
      Files.writeString(manifest, content.toString());
      assertNull(store.load(saved.id()).data().rehearsal());
      workspace.openProject(store.load(saved.id()).data());
      assertEquals(1, workspace.snapshot().score().parts().size());
      content.addProperty("schemaVersion", 999);
      Files.writeString(manifest, content.toString());
      assertThrows(java.io.IOException.class, () -> store.load(saved.id()));
    }
  }
}
