package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void libraryNameSurvivesAutosaveAndRestartWithoutChangingMusicalTitle() throws Exception {
    var store = new ProjectStore(temporaryDirectory);
    var original = store.save(null, data("Written title", "<original/>"));
    store.rename(original.id(), "  Estudo diário  ");
    assertEquals("Written title", store.load(original.id()).data().title());
    store.save(original.id(), data("Written title", "<edited/>"));
    var restarted = new ProjectStore(temporaryDirectory);
    assertEquals("Estudo diário", restarted.list().getFirst().title());
    assertEquals("<edited/>", restarted.load(original.id()).data().xml());
  }

  @Test
  void duplicateRetainsProvenanceAndIsIndependentOfTheOriginal() throws Exception {
    var store = new ProjectStore(temporaryDirectory);
    var originalData =
        new ProjectStore.Data(
            "Study",
            "study.musicxml",
            "a".repeat(64),
            "parser-v1",
            "MUSICXML",
            List.of("Original warning"),
            null,
            "<original/>");
    var original = store.save(null, originalData);
    var duplicate = store.duplicate(original.id(), "Segunda tentativa");
    assertFalse(original.id().equals(duplicate.id()));
    assertEquals(originalData, store.load(duplicate.id()).data());
    store.save(duplicate.id(), data("Study", "<copy-edited/>"));
    assertEquals(originalData, store.load(original.id()).data());
    assertEquals(
        "Segunda tentativa",
        store.list().stream()
            .filter(p -> p.id().equals(duplicate.id()))
            .findFirst()
            .orElseThrow()
            .title());
  }

  @Test
  void invalidNamesAndCorruptedSourcesCannotChangeTheLibrary() throws Exception {
    var store = new ProjectStore(temporaryDirectory);
    var original = store.save(null, data("Study", "<original/>"));
    for (String invalid : List.of(" ", "a".repeat(201), "two\nlines", "null\u0000name")) {
      assertThrows(IllegalArgumentException.class, () -> store.rename(original.id(), invalid));
      assertThrows(IllegalArgumentException.class, () -> store.duplicate(original.id(), invalid));
    }
    assertEquals("Study", store.list().getFirst().title());
    Files.writeString(
        temporaryDirectory.resolve(original.id()).resolve("score.musicxml"), "<tampered/>");
    assertThrows(IOException.class, () -> store.duplicate(original.id(), "Copy"));
    assertEquals(1, store.list().size());
  }

  @Test
  void failedLibraryWritePreservesTheOriginalAndLeavesNoDuplicate() throws Exception {
    var store = new ProjectStore(temporaryDirectory);
    var original = store.save(null, data("Study", "<original/>"));
    var failing =
        new ProjectStore(
            temporaryDirectory,
            (path, bytes) -> {
              throw new IOException("Disk full");
            });
    assertThrows(IOException.class, () -> failing.rename(original.id(), "Renamed"));
    assertThrows(IOException.class, () -> failing.duplicate(original.id(), "Copy"));
    assertEquals(1, store.list().size());
    assertEquals("Study", store.list().getFirst().title());
    assertEquals("<original/>", store.load(original.id()).data().xml());
  }

  @Test
  void doesNotRestoreBackupWhoseScoreChecksumIsInvalid() throws Exception {
    var store = new ProjectStore(temporaryDirectory);
    var saved =
        store.save(
            null,
            new ProjectStore.Data(
                "Study",
                "study.musicxml",
                "a".repeat(64),
                "musicxml-m2-v1",
                "MUSICXML",
                List.of(),
                null,
                "<x/>"));
    Path project = temporaryDirectory.resolve(saved.id());
    Path backup =
        temporaryDirectory.resolve(".backup-" + saved.id() + "-" + java.util.UUID.randomUUID());
    Files.move(project, backup);
    Files.writeString(backup.resolve("score.musicxml"), "<corrupted/>", StandardCharsets.UTF_8);

    assertTrue(store.list().isEmpty());
    assertFalse(Files.exists(project));
  }

  @Test
  void concurrentAutosavesKeepOneCompleteRevisionAndValidManifest() throws Exception {
    var store = new ProjectStore(temporaryDirectory);
    var initial = store.save(null, data("Initial", "<initial/>"));
    var ready = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(() -> saveAfter(ready, store, initial.id(), data("First", "<first/>")));
      var second =
          executor.submit(() -> saveAfter(ready, store, initial.id(), data("Second", "<second/>")));
      ready.countDown();
      first.get();
      second.get();
    }

    var loaded = store.load(initial.id()).data();
    assertTrue(
        (loaded.title().equals("First") && loaded.xml().equals("<first/>"))
            || (loaded.title().equals("Second") && loaded.xml().equals("<second/>")));
    assertEquals(1, store.list().size());
  }

  @Test
  void failedStagingWriteLeavesThePreviousProjectUntouched() throws Exception {
    var store = new ProjectStore(temporaryDirectory);
    var initial = store.save(null, data("Saved", "<saved/>"));
    ProjectStore.DurableWriter failManifest =
        (path, content) -> {
          if (path.getFileName().toString().equals("manifest.json"))
            throw new IOException("Simulated manifest write failure");
          Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
          }
        };
    var failingStore = new ProjectStore(temporaryDirectory, failManifest);

    assertThrows(
        IOException.class, () -> failingStore.save(initial.id(), data("Unsaved", "<unsaved/>")));

    var recovered = store.load(initial.id()).data();
    assertEquals("Saved", recovered.title());
    assertEquals("<saved/>", recovered.xml());
    try (var entries = Files.list(temporaryDirectory)) {
      assertTrue(entries.noneMatch(path -> path.getFileName().toString().startsWith(".pending-")));
    }
  }

  private static ProjectStore.Saved saveAfter(
      java.util.concurrent.CountDownLatch ready,
      ProjectStore store,
      String id,
      ProjectStore.Data data)
      throws Exception {
    ready.await();
    return store.save(id, data);
  }

  private static ProjectStore.Data data(String title, String xml) {
    return new ProjectStore.Data(
        title,
        "study.musicxml",
        "a".repeat(64),
        "musicxml-m3-stax-v1",
        "MUSICXML",
        List.of(),
        null,
        xml);
  }
}
