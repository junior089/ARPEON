package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateSettingsTest {
  @TempDir Path root;

  @Test
  void inactiveBuildNeverLoadsNativeCodeOrConnects() throws Exception {
    var config = config("false", "", "");
    try (var updater =
        new WindowsUpdater(
            UpdateSettings.load(config),
            "0.1.0",
            () -> true,
            () -> {
              throw new AssertionError("Unexpected shutdown");
            })) {
      updater.initialize(root.resolve("does-not-exist.dll"));
      assertFalse(updater.status().enabled());
      assertThrows(java.io.IOException.class, updater::check);
    }
  }

  @Test
  void enabledChannelsRequireHttpsAndAnEd25519Key() throws Exception {
    String key = Base64.getEncoder().encodeToString(new byte[32]);
    assertThrows(
        java.io.IOException.class,
        () -> UpdateSettings.load(config("true", "http://localhost/feed", key)));
    assertThrows(
        java.io.IOException.class,
        () -> UpdateSettings.load(config("true", "https://localhost/feed", "invalid")));
    assertTrue(UpdateSettings.load(config("true", "https://localhost/feed", key)).enabled());
    assertThrows(
        java.io.IOException.class,
        () -> UpdateSettings.load(config("false", "https://localhost/feed", "")));
  }

  private Path config(String enabled, String url, String key) throws Exception {
    return Files.writeString(
        root.resolve("updates.properties"),
        "enabled="
            + enabled
            + "\nchannel=testing\nappcastUrl="
            + url
            + "\npublicKey="
            + key
            + "\n");
  }
}
