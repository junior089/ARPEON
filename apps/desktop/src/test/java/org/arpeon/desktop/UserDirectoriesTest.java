package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserDirectoriesTest {
  @TempDir Path home;

  @Test
  void preservesLegacyProfilesAndAllowsExplicitIsolation() throws Exception {
    Path legacy = Files.createDirectory(home.resolve(".arpeon"));
    assertEquals(legacy, UserDirectories.resolve(home, "Windows", Map.of(), null));
    assertEquals(
        home.resolve("isolated"),
        UserDirectories.resolve(home, "Windows", Map.of(), home.resolve("isolated").toString()));
  }

  @Test
  void selectsPlatformDataOutsideInstallation() {
    assertEquals(
        home.resolve("local/ARPEON"),
        UserDirectories.resolve(
            home, "Windows", Map.of("LOCALAPPDATA", home.resolve("local").toString()), null));
    assertEquals(
        home.resolve("Library/Application Support/ARPEON"),
        UserDirectories.resolve(home, "Mac OS X", Map.of(), null));
    assertEquals(
        home.resolve(".local/share/arpeon"),
        UserDirectories.resolve(home, "Linux", Map.of("XDG_DATA_HOME", "relative"), null));
  }

  @Test
  void releasesInstanceLockAndDurablyReplacesData() throws Exception {
    try (var first = InstanceLock.acquire(home)) {
      org.junit.jupiter.api.Assertions.assertNotNull(first);
      assertThrows(java.io.IOException.class, () -> InstanceLock.acquire(home));
    }
    try (var second = InstanceLock.acquire(home)) {
      org.junit.jupiter.api.Assertions.assertNotNull(second);
      Path file = home.resolve("settings.json");
      DurableFiles.replace(file, "first".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      DurableFiles.replace(file, "second".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals("second", Files.readString(file));
      try (var entries = Files.list(home)) {
        assertEquals(2, entries.count());
      }
    }
  }
}
