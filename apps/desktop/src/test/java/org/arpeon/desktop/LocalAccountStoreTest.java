package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAccountStoreTest {
  @TempDir Path root;

  @Test
  void copiesLegacyScopesAndActivatesOnlyVerifiedCopyWithoutMovingOriginals() throws Exception {
    Files.createDirectories(root.resolve("projects/retained"));
    Files.writeString(root.resolve("projects/retained/score.musicxml"), "original score bytes");
    Files.writeString(root.resolve("preferences.json"), PreferencesStoreTest.settings().toString());
    Files.writeString(root.resolve("practice-history.json"), "[]");
    Files.writeString(root.resolve("audio-preferences.json"), "device settings");
    var account = new LocalAccountStore(root, 8);
    Path selected = account.profileDirectory(account.snapshot().activeProfileId());
    assertEquals(
        -1,
        Files.mismatch(
            root.resolve("projects/retained/score.musicxml"),
            selected.resolve("projects/retained/score.musicxml")));
    assertEquals(125, new PreferencesStore(selected.resolve("preferences.json")).load().zoom());
    assertFalse(Files.exists(selected.resolve("audio-preferences.json")));
    assertFalse(account.snapshot().openLastProfile());
    Files.writeString(root.resolve("projects/retained/score.musicxml"), "changed legacy copy");
    var reopened = new LocalAccountStore(root, 8);
    assertEquals(account.snapshot(), reopened.snapshot());
    assertEquals(
        "original score bytes",
        Files.readString(selected.resolve("projects/retained/score.musicxml")));
  }

  @Test
  void limitsConcurrentCreationAndKeepsArchivedProfilesWithinTheQuota() throws Exception {
    var account = new LocalAccountStore(root, 8);
    var created = new AtomicInteger();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int index = 0; index < 20; index++)
        tasks.add(
            pool.submit(
                () -> {
                  try {
                    account.create("Piano", "blue", "piano", List.of("violin"));
                    created.incrementAndGet();
                  } catch (IllegalStateException full) {
                    assertTrue(full.getMessage().contains("limite"));
                  } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                  }
                }));
      for (var task : tasks) task.get();
    }
    assertEquals(7, created.get());
    assertEquals(8, account.view(false).profiles().size());
    assertEquals(8, new LocalAccountStore(root, 8).snapshot().profiles().size());
    String id = account.snapshot().profiles().getLast().id();
    account.archive(id, true);
    assertThrows(
        IllegalStateException.class, () -> account.create("Nono", "amber", "flute", List.of()));
    assertThrows(IllegalStateException.class, () -> account.select(id));
    account.archive(id, false);
    account.select(id);
    assertThrows(IllegalStateException.class, () -> account.archive(id, true));
  }

  @Test
  void validatesMetadataAndPersistsIdentityEditsSelectionAndExplicitResumeConsent()
      throws Exception {
    var account = new LocalAccountStore(root, 3);
    var piano = account.create("  Piano e violino  ", "blue", "piano", List.of("violin"));
    assertEquals("Piano e violino", piano.name());
    assertThrows(
        IllegalArgumentException.class,
        () -> account.create("\u202Ehidden", "rose", "piano", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> account.edit(piano.id(), "Piano", "remote-url", "piano", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> account.edit(piano.id(), "Piano", "blue", "unknown", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> account.edit(piano.id(), "Piano", "blue", "piano", List.of("piano")));
    assertThrows(IllegalArgumentException.class, () -> account.profileDirectory("../../projects"));
    account.edit(piano.id(), "Piano", "sage", "piano", List.of("violin", "flute"));
    account.select(piano.id());
    account.preferences("Estudos locais", true);
    var reopened = new LocalAccountStore(root, 3);
    assertEquals(account.snapshot(), reopened.snapshot());
    assertEquals(piano.id(), reopened.snapshot().activeProfileId());
    assertEquals(List.of("violin", "flute"), reopened.profile(piano.id()).secondaryInstrumentIds());
    assertTrue(reopened.snapshot().openLastProfile());
    assertEquals(3, reopened.view(false).maxProfiles());
  }

  @Test
  void failedRegistryWritePreservesAccountAndRemovesTheUnregisteredEmptyDirectory()
      throws Exception {
    var account = new LocalAccountStore(root, 8);
    var before = account.snapshot();
    Path backup = root.resolve("identity/account.json.backup");
    Files.createDirectory(backup);
    Files.writeString(backup.resolve("blocked"), "keep");
    assertThrows(IOException.class, () -> account.create("Piano", "blue", "piano", List.of()));
    assertEquals(before, account.snapshot());
    assertEquals(before, new LocalAccountStore(root, 8).snapshot());
    try (var profiles = Files.list(root.resolve("identity/profiles"))) {
      assertEquals(1, profiles.count());
    }
  }

  @Test
  void neverRecreatesCorruptIdentityOrOverwritesExternalRegistryChanges() throws Exception {
    var account = new LocalAccountStore(root, 8);
    Path file = root.resolve("identity/account.json");
    Files.writeString(file, "{broken");
    assertThrows(IOException.class, () -> new LocalAccountStore(root, 8));
    assertThrows(IOException.class, () -> account.preferences("New", true));
    assertEquals("{broken", Files.readString(file));
    assertTrue(Files.isDirectory(root.resolve("identity/profiles")));
  }

  @Test
  void rejectsUnsafeLegacyDepthBeforeActivatingAnAccount() throws Exception {
    Path deep = root.resolve("projects/a/b/c/d/e/f/g/h/i");
    Files.createDirectories(deep);
    Files.writeString(deep.resolve("data"), "preserve");
    assertThrows(IOException.class, () -> new LocalAccountStore(root, 8));
    assertFalse(Files.exists(root.resolve("identity")));
    assertEquals("preserve", Files.readString(deep.resolve("data")));
  }

  @Test
  void refusesCoercedConsentUnknownFieldsAndUnsupportedVersions() throws Exception {
    new LocalAccountStore(root, 8);
    Path file = root.resolve("identity/account.json");
    String original = Files.readString(file);
    for (String invalid :
        List.of(
            original.replace("\"openLastProfile\":false", "\"openLastProfile\":\"true\""),
            original.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            original.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"unexpected\":true"))) {
      Files.writeString(file, invalid);
      assertThrows(IOException.class, () -> new LocalAccountStore(root, 8));
      assertEquals(invalid, Files.readString(file));
    }
  }
}
