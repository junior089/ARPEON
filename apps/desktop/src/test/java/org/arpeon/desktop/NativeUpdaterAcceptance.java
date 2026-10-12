package org.arpeon.desktop;

import java.nio.file.Path;

/** Exercises the real pinned native DLL without checking a feed or installing an update. */
public final class NativeUpdaterAcceptance {
  private NativeUpdaterAcceptance() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException("Expected DLL and isolated test public key");
    var settings =
        new UpdateSettings(
            true, "testing", "https://localhost/isolated-native-binding-test", args[1]);
    Path consent = java.nio.file.Files.createTempFile("arpeon-updater-acceptance-", ".txt");
    var preferences = new UpdatePreferences(consent);
    preferences.save(false);
    try (var updater =
        new WindowsUpdater(
            settings,
            "0.1.0",
            () -> false,
            () -> {
              throw new AssertionError("Unexpected shutdown");
            },
            preferences)) {
      updater.initialize(Path.of(args[0]));
      if (!updater.status().enabled())
        throw new AssertionError("Native updater was not initialized");
      if (updater.status().automaticChecking())
        throw new AssertionError("Acceptance must not make network requests");
      System.out.println(
          "WinSparkle DLL initialized and cleaned up; automatic checks disabled. No feed was"
              + " requested.");
    } finally {
      java.nio.file.Files.deleteIfExists(consent);
    }
  }
}
