package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectProvenanceTest {
  @TempDir Path root;

  private static byte[] fixture(String filename) throws IOException {
    return Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), filename));
  }

  @Test
  void originalAndIdentitiesSurviveEditingSaveDuplicateAndRestart() throws Exception {
    byte[] bytes = fixture("first-notes.musicxml");
    var store = new ProjectStore(root);
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(new ByteArrayInputStream(bytes), "first-notes.musicxml");
      var initial = workspace.projectData();
      assertArrayEquals(bytes, initial.original().copyBytes());
      assertNotEquals(
          ProjectProvenance.hash(bytes),
          ProjectProvenance.hash(initial.xml().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      var saved = store.save(null, initial);
      var details = store.details(saved.id());
      assertEquals(initial.provenance(), details.provenance());
      assertNull(details.provenance().edition());
      assertEquals("NOT_REVIEWED", details.provenance().rights().status());
      assertEquals("UNKNOWN", details.provenance().rights().redistribution());
      assertEquals(initial.provenance().work().id(), initial.provenance().arrangement().workId());
      var loaded = new ProjectStore(root).load(saved.id()).data();
      workspace.openProject(loaded);
      var edited = workspace.projectData();
      var changed =
          new ProjectStore.Data(
              edited.title(),
              edited.filename(),
              edited.sha256(),
              edited.parserVersion(),
              edited.sourceKind(),
              edited.importWarnings(),
              edited.source(),
              edited.xml().replace("First notes", "Edited title"),
              edited.rehearsal(),
              edited.provenance(),
              edited.original());
      store.save(saved.id(), changed);
      store.rename(saved.id(), "Renamed library item");
      var copy = store.duplicate(saved.id(), "Independent practice copy");
      assertEquals(initial.provenance(), store.load(copy.id()).data().provenance());
      assertArrayEquals(bytes, new ProjectStore(root).original(copy.id()).copyBytes());
      store.delete(saved.id());
      assertArrayEquals(bytes, store.original(copy.id()).copyBytes());
    }
  }

  @Test
  void keepsCompressedPackageBytesAndDoesNotMergeWorksByTitle() throws Exception {
    try (var workspace = new ScoreWorkspace()) {
      var store = new ProjectStore(root);
      for (String filename :
          List.of("badinerie-for-flute-by-js-bach.mxl", "badinerie-for-flute-by-js-bach.mscz")) {
        byte[] bytes = fixture(filename);
        workspace.openMxl(bytes, filename);
        var saved = store.save(null, workspace.projectData());
        assertArrayEquals(bytes, store.original(saved.id()).copyBytes());
        assertEquals(
            ProjectProvenance.hash(bytes),
            store.details(saved.id()).provenance().sourceAsset().sha256());
      }
      workspace.open(fixture("first-notes.musicxml"), "first-notes.musicxml");
      var first = workspace.projectData().provenance();
      workspace.open(fixture("first-notes.musicxml"), "first-notes.musicxml");
      var second = workspace.projectData().provenance();
      assertNotEquals(first.work().id(), second.work().id());
      assertEquals(first.sourceAsset().id(), second.sourceAsset().id());
    }
  }

  @Test
  void corruptedOrMissingOriginalIsNotDownloadedDuplicatedOrRecovered() throws Exception {
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(fixture("first-notes.musicxml"), "first-notes.musicxml");
      var store = new ProjectStore(root);
      var saved = store.save(null, workspace.projectData());
      Path original = root.resolve(saved.id()).resolve("source.bin");
      byte[] changed = Files.readAllBytes(original);
      changed[0] ^= 1;
      Files.write(original, changed);
      assertThrows(IOException.class, () -> store.original(saved.id()));
      assertThrows(IOException.class, () -> store.details(saved.id()));
      assertThrows(IOException.class, () -> store.duplicate(saved.id(), "Copy"));
      Path backup = root.resolve(".backup-" + saved.id() + "-" + java.util.UUID.randomUUID());
      Files.move(root.resolve(saved.id()), backup);
      assertTrue(store.list().isEmpty());
      assertFalse(Files.exists(root.resolve(saved.id())));
    }
  }

  @Test
  void failedOriginalWritePreservesPreviousWholeProject() throws Exception {
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(fixture("first-notes.musicxml"), "first-notes.musicxml");
      var store = new ProjectStore(root);
      var saved = store.save(null, workspace.projectData());
      byte[] before = Files.readAllBytes(root.resolve(saved.id()).resolve("manifest.json"));
      var failing =
          new ProjectStore(
              root,
              (path, content) -> {
                if (path.getFileName().toString().equals("source.bin"))
                  throw new IOException("Disk unavailable");
                Files.write(path, content);
              });
      assertThrows(IOException.class, () -> failing.save(saved.id(), workspace.projectData()));
      assertArrayEquals(
          before, Files.readAllBytes(root.resolve(saved.id()).resolve("manifest.json")));
      assertArrayEquals(fixture("first-notes.musicxml"), store.original(saved.id()).copyBytes());
      try (var files = Files.list(root)) {
        assertEquals(1, files.count());
      }
    }
  }

  @Test
  void readsLegacyManifestWithoutClaimingToReconstructItsOriginal() throws Exception {
    var store = new ProjectStore(root);
    var saved =
        store.save(
            null,
            new ProjectStore.Data(
                "Legacy",
                "old.musicxml",
                "a".repeat(64),
                "old-parser",
                "MUSICXML",
                List.of(),
                null,
                "<legacy/>"));
    Path manifest = root.resolve(saved.id()).resolve("manifest.json");
    Files.writeString(
        manifest, Files.readString(manifest).replace("\"schemaVersion\":5", "\"schemaVersion\":2"));
    assertNull(store.details(saved.id()).provenance());
    assertNull(store.original(saved.id()));
    assertEquals("<legacy/>", store.load(saved.id()).data().xml());
  }

  @Test
  void immutableBytesAndFailedImportCannotReplaceTheCurrentOrigin() throws Exception {
    byte[] bytes = fixture("first-notes.musicxml");
    var original = new OriginalScore("first-notes.musicxml", bytes);
    byte saved = bytes[0];
    bytes[0] ^= 1;
    assertEquals(saved, original.copyBytes()[0]);
    var copy = original.copyBytes();
    copy[0] ^= 1;
    assertEquals(saved, original.copyBytes()[0]);
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(original.copyBytes(), original.filename());
      var before = workspace.projectData().provenance();
      assertThrows(
          IOException.class, () -> workspace.open(new byte[] {1, 2, 3}, "invalid.musicxml"));
      assertEquals(before, workspace.projectData().provenance());
    }
    assertThrows(
        IOException.class, () -> new OriginalScore("../outside.musicxml", original.copyBytes()));
    assertThrows(
        IOException.class, () -> new OriginalScore("bad\r\nname.xml", original.copyBytes()));
  }

  @Test
  void existingProjectCannotSilentlyChangeOrDropItsSourceIdentity() throws Exception {
    var store = new ProjectStore(root);
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(fixture("first-notes.musicxml"), "first-notes.musicxml");
      var initial = workspace.projectData();
      var saved = store.save(null, initial);
      workspace.open(fixture("two-parts.musicxml"), "two-parts.musicxml");
      assertThrows(IOException.class, () -> store.save(saved.id(), workspace.projectData()));
      assertThrows(
          IOException.class,
          () ->
              store.save(
                  saved.id(),
                  new ProjectStore.Data(
                      initial.title(),
                      initial.filename(),
                      initial.sha256(),
                      initial.parserVersion(),
                      initial.sourceKind(),
                      initial.importWarnings(),
                      initial.source(),
                      initial.xml())));
      assertEquals(initial.provenance(), store.details(saved.id()).provenance());
      assertArrayEquals(initial.original().copyBytes(), store.original(saved.id()).copyBytes());
      assertNotEquals(saved.id(), store.save(null, workspace.projectData()).id());
    }
  }

  @Test
  void missingMetadataCannotHideARetainedOriginalAndWhitespaceMetadataRemainsValid()
      throws Exception {
    var store = new ProjectStore(root);
    try (var workspace = new ScoreWorkspace()) {
      byte[] bytes =
          new String(fixture("first-notes.musicxml"), java.nio.charset.StandardCharsets.UTF_8)
              .replace("First notes", "First\nnotes")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      workspace.open(bytes, "first-notes.musicxml");
      var saved = store.save(null, workspace.projectData());
      assertEquals("First\nnotes", store.details(saved.id()).provenance().work().title());
      Path manifest = root.resolve(saved.id()).resolve("manifest.json");
      var data =
          com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      data.remove("provenance");
      Files.writeString(manifest, data.toString());
      assertThrows(IOException.class, () -> store.details(saved.id()));
      assertThrows(IOException.class, () -> store.original(saved.id()));
      assertThrows(IOException.class, () -> store.save(saved.id(), workspace.projectData()));
      assertArrayEquals(bytes, Files.readAllBytes(root.resolve(saved.id()).resolve("source.bin")));
    }
  }

  @Test
  void malformedNestedEvidenceIsReportedAsInvalidDataAndDoesNotBreakTheLibrary() throws Exception {
    var store = new ProjectStore(root);
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(fixture("first-notes.musicxml"), "first-notes.musicxml");
      var saved = store.save(null, workspace.projectData());
      Path manifest = root.resolve(saved.id()).resolve("manifest.json");
      var data =
          com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      data.getAsJsonObject("provenance").getAsJsonObject("work").remove("externalIdentifiers");
      Files.writeString(manifest, data.toString());
      assertThrows(IOException.class, () -> store.load(saved.id()));
      assertTrue(store.list().isEmpty());
      assertArrayEquals(
          fixture("first-notes.musicxml"),
          Files.readAllBytes(root.resolve(saved.id()).resolve("source.bin")));
    }
  }
}
