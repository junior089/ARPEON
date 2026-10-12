package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdatePreferencesTest {
  @TempDir Path profile;

  @Test
  void automaticChecksRequireConsentAndSurviveAReplacementPackage() throws Exception {
    Path file = profile.resolve("update-preferences.txt");
    var preferences = new UpdatePreferences(file);
    assertFalse(preferences.automatic());
    preferences.save(true);
    assertTrue(new UpdatePreferences(file).automatic());
    preferences.save(false);
    assertFalse(new UpdatePreferences(file).automatic());
  }

  @Test
  void corruptOrOversizedPreferencesNeverEnableNetworkChecks() throws Exception {
    Path file = Files.writeString(profile.resolve("updates.txt"), "true-extra");
    assertFalse(new UpdatePreferences(file).automatic());
    Files.writeString(file, "true" + " ".repeat(64));
    assertFalse(new UpdatePreferences(file).automatic());
  }
}
