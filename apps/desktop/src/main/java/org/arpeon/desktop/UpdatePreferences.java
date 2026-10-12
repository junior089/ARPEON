package org.arpeon.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Update consent belongs to the user profile, never to an application package. */
final class UpdatePreferences {
  private final Path file;

  UpdatePreferences(Path file) {
    this.file = file.toAbsolutePath().normalize();
  }

  synchronized boolean automatic() {
    if (!Files.exists(file)) return false;
    try {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 32)
        throw new IOException("Unsafe update preferences");
      String value = Files.readString(file, StandardCharsets.UTF_8).strip();
      if (!value.equals("true") && !value.equals("false"))
        throw new IOException("Invalid update preferences");
      return Boolean.parseBoolean(value);
    } catch (IOException failure) {
      System.getLogger(UpdatePreferences.class.getName())
          .log(System.Logger.Level.WARNING, "Automatic update checks disabled", failure);
      return false;
    }
  }

  synchronized void save(boolean enabled) throws IOException {
    DurableFiles.replace(file, Boolean.toString(enabled).getBytes(StandardCharsets.UTF_8));
  }
}
