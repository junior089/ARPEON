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
