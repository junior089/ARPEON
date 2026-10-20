package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditionEvidenceTest {
  @TempDir Path root;

  private ProjectStore.Data imported() throws Exception {
    try (var workspace = new ScoreWorkspace()) {
      workspace.open(
          Files.readAllBytes(
              Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml")),
          "first-notes.musicxml");
      return workspace.projectData();
    }
  }

  @Test
  void appendsCorrectionsAndWithdrawalWithoutChangingNotesOriginOrRights() throws Exception {
    var store = new ProjectStore(root);
    var initial = imported();
    var id = store.save(null, initial).id();
    String asset = initial.provenance().sourceAsset().id();
    var first =
        store.identifyEdition(
            id,
            asset,
            0,
            "  Edição de estudo  ",
            "Catálogo local, página 1",
            "https://example.org/catalogue/1");
    assertEquals("Edição de estudo", first.history().getFirst().edition().title());
    assertNull(store.details(id).provenance().edition());
    store.identifyEdition(id, asset, 1, "Edição corrigida", "Chapa 123, página de rosto", "");
    var withdrawn =
        store.identifyEdition(id, asset, 2, "", "A referência corresponde a outra edição", "");
    assertEquals(3, withdrawn.history().size());
    assertEquals(first.history().getFirst(), withdrawn.history().getFirst());
    assertNull(withdrawn.history().getLast().edition());
    assertThrows(
        IllegalStateException.class,
        () -> store.identifyEdition(id, asset, 3, "", "Sem identificação", ""));
    assertEquals(initial.xml(), store.load(id).data().xml());
    assertEquals(initial.provenance(), store.details(id).provenance());
    assertArrayEquals(initial.original().copyBytes(), store.original(id).copyBytes());
    assertEquals(withdrawn, new ProjectStore(root).editions(id));
    var copy = store.duplicate(id, "Cópia");
    assertEquals(withdrawn, store.editions(copy.id()));
    try (var workspace = new ScoreWorkspace()) {
      workspace.openProject(store.load(id).data());
      assertEquals(withdrawn.history(), workspace.projectData().editions());
      var savedAsNew = store.save(null, workspace.projectData());
      assertEquals(withdrawn, store.editions(savedAsNew.id()));
    }
    store.save(id, initial);
    assertEquals(withdrawn, store.editions(id), "An old autosave must preserve the latest history");
  }

  @Test
  void rejectsStaleForeignAndMalformedDeclarationsAndEnforcesQuota() throws Exception {
    var store = new ProjectStore(root);
    var data = imported();
    String id = store.save(null, data).id(), asset = data.provenance().sourceAsset().id();
    assertThrows(
        IllegalStateException.class,
        () -> store.identifyEdition(id, "foreign", 0, "Edition", "Evidence", ""));
    assertThrows(
        IllegalStateException.class,
        () -> store.identifyEdition(id, asset, 1, "Edition", "Evidence", ""));
    for (String url :
        List.of(
            "http://example.org/1",
            "https://user:pass@example.org/1",
            "https://example.org:443/1",
            "https://example.org/1?x=1",
            "https://example.org/1#x",
            "file:///secret"))
      assertThrows(
          IllegalArgumentException.class,
          () -> store.identifyEdition(id, asset, 0, "Edition", "Evidence", url));
    for (String citation : List.of("", " ", "a\nb", "a\u202eb", "a".repeat(1001)))
      assertThrows(
          IllegalArgumentException.class,
          () -> store.identifyEdition(id, asset, 0, "Edition", citation, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.identifyEdition(id, asset, 0, "x".repeat(201), "Evidence", ""));
    assertTrue(store.editions(id).history().isEmpty());
    for (int revision = 0; revision < EditionEvidence.MAX_ENTRIES; revision++)
      store.identifyEdition(id, asset, revision, "Edição " + revision, "Catálogo " + revision, "");
    assertThrows(
        IllegalStateException.class,
        () -> store.identifyEdition(id, asset, 16, "Edition", "Evidence", ""));
    assertEquals(16, store.editions(id).history().size());
  }

  @Test
  void concurrentDeclarationsHaveOneWinnerAndFailedWritePreservesHistory() throws Exception {
    var store = new ProjectStore(root);
    var data = imported();
    String id = store.save(null, data).id(), asset = data.provenance().sourceAsset().id();
    Callable<Boolean> update =
        () -> {
          try {
            store.identifyEdition(id, asset, 0, "Edition", "Evidence", "");
            return true;
          } catch (IllegalStateException conflict) {
            return false;
          }
        };
    try (var executor = Executors.newFixedThreadPool(2)) {
      var results = executor.invokeAll(List.of(update, update));
      assertNotEquals(results.getFirst().get(), results.getLast().get());
    }
    byte[] manifest = Files.readAllBytes(root.resolve(id).resolve("manifest.json"));
    var failing =
        new ProjectStore(
            root,
            (path, content) -> {
              if (path.getFileName().toString().equals("manifest.json"))
                throw new IOException("Disk unavailable");
              Files.write(path, content);
            });
    assertThrows(
        IOException.class,
        () -> failing.identifyEdition(id, asset, 1, "Correction", "Evidence", ""));
    assertArrayEquals(manifest, Files.readAllBytes(root.resolve(id).resolve("manifest.json")));
    assertEquals(1, store.editions(id).history().size());
    try (var files = Files.list(root)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void boundedManifestFailureDoesNotTruncateEarlierEvidence() throws Exception {
    var store = new ProjectStore(root);
    var data = imported();
    String id = store.save(null, data).id(), asset = data.provenance().sourceAsset().id();
    int revision = 0;
    boolean rejected = false;
    while (revision < 16) {
      byte[] previous = Files.readAllBytes(root.resolve(id).resolve("manifest.json"));
      try {
        store.identifyEdition(
            id,
            asset,
            revision,
            "版".repeat(200),
            "証".repeat(1000),
            "https://example.org/" + "%E8%A8%BC".repeat(108));
        revision++;
      } catch (IOException limit) {
        rejected = true;
        assertArrayEquals(previous, Files.readAllBytes(root.resolve(id).resolve("manifest.json")));
        assertEquals(revision, store.editions(id).history().size());
        break;
      }
    }
    assertTrue(rejected);
  }

  @Test
  void validatesStoredLinksAndReadsAllOlderSchemasWithoutInventingHistory() throws Exception {
    var store = new ProjectStore(root);
    var data = imported();
    String id = store.save(null, data).id(), asset = data.provenance().sourceAsset().id();
    Path path = root.resolve(id).resolve("manifest.json");
    String original = Files.readString(path);
    for (int schema : List.of(1, 2, 3)) {
      var legacy = JsonParser.parseString(original).getAsJsonObject();
      legacy.addProperty("schemaVersion", schema);
      legacy.remove("editions");
      Files.writeString(path, legacy.toString());
      assertTrue(new ProjectStore(root).editions(id).history().isEmpty());
    }
    Files.writeString(path, original);
    store.identifyEdition(id, asset, 0, "Edition", "Evidence", "");
    String valid = Files.readString(path);
    for (String field : List.of("sourceAssetId", "recordedAt", "citation")) {
      var broken = JsonParser.parseString(valid).getAsJsonObject();
      broken.getAsJsonArray("editions").get(0).getAsJsonObject().remove(field);
      Files.writeString(path, broken.toString());
      assertThrows(IOException.class, () -> store.load(id));
      assertTrue(store.list().isEmpty());
    }
    var broken = JsonParser.parseString(valid).getAsJsonObject();
    broken
        .getAsJsonArray("editions")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("edition")
        .addProperty("workId", java.util.UUID.randomUUID().toString());
    Files.writeString(path, broken.toString());
    assertThrows(IOException.class, () -> store.load(id));
    assertArrayEquals(
        data.original().copyBytes(), Files.readAllBytes(root.resolve(id).resolve("source.bin")));
  }
}
