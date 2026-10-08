package org.arpeon.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Local summaries only; no microphone samples, account identifiers or score contents. */
final class PracticeHistory {
  private static final long MAX_BYTES = 64 * 1024;
  private static final int MAX_ENTRIES = 50;
  private final Path file;
  private final Gson json = new Gson();

  PracticeHistory(Path file) {
    this.file = file;
  }

  synchronized List<PracticeWorkspace.Summary> list() throws IOException {
    if (!Files.exists(file)) return List.of();
    if (Files.size(file) > MAX_BYTES) throw new IOException("Practice history is too large");
    try {
      PracticeWorkspace.Summary[] entries =
          json.fromJson(
              Files.readString(file, StandardCharsets.UTF_8), PracticeWorkspace.Summary[].class);
      if (entries == null || entries.length > MAX_ENTRIES)
        throw new IOException("Invalid practice history");
      for (var entry : entries)
        if (entry == null
            || entry.id() == null
            || entry.kind() == null
            || entry.keyId() == null
            || entry.startedAt() == null
            || entry.endedAt() == null) throw new IOException("Invalid practice history");
      return List.of(entries);
    } catch (JsonParseException | IllegalStateException failure) {
      throw new IOException("Invalid practice history", failure);
    }
  }

  synchronized List<PracticeWorkspace.Summary> add(PracticeWorkspace.Summary summary)
      throws IOException {
    var entries = new ArrayList<>(list());
    entries.removeIf(previous -> previous.id().equals(summary.id()));
    entries.add(0, summary);
    if (entries.size() > MAX_ENTRIES) entries.subList(MAX_ENTRIES, entries.size()).clear();
    Files.createDirectories(file.getParent());
    Path temporary = Files.createTempFile(file.getParent(), "practice-", ".tmp");
    try {
      Files.writeString(temporary, json.toJson(entries), StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
    return List.copyOf(entries);
  }

  static PracticeHistory defaultHistory() {
    return new PracticeHistory(
        Path.of(System.getProperty("user.home"), ".arpeon", "practice-history.json"));
  }
}
