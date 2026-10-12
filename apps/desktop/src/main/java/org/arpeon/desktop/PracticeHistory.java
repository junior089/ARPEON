package org.arpeon.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Local summaries only; no microphone samples, account identifiers or score contents. */
final class PracticeHistory {
  private static final long MAX_BYTES = 8L * 1024 * 1024;
  private static final int MAX_ENTRIES = 5000;
  private final Path file;
  private final Gson json = new Gson();

  PracticeHistory(Path file) {
    this.file = file;
  }

  record Page(
      List<PracticeWorkspace.Summary> entries,
      int page,
      int pageSize,
      int matchingCount,
      int totalCount,
      int completedCount) {}

  synchronized Page page(
      int requestedPage, int pageSize, String kind, String outcome, Instant from, Instant until)
      throws IOException {
    if (requestedPage < 0 || pageSize < 1 || pageSize > 50)
      throw new IllegalArgumentException("Página inválida; use de 1 a 50 tentativas por página.");
    if (!kind.isEmpty()
        && !Set.of("NOTE", "LONG_TONE", "SCALE", "ARPEGGIO", "RHYTHM").contains(kind))
      throw new IllegalArgumentException("Selecione um tipo de exercício válido.");
    if (!Set.of("", "completed", "interrupted").contains(outcome))
      throw new IllegalArgumentException("Selecione um resultado válido.");
    if (from != null && until != null && !from.isBefore(until))
      throw new IllegalArgumentException("A data inicial deve vir antes do fim do período.");
    var all = list();
    // Validate dates before ordering/filtering; malformed storage is an error, never an empty page.
    try {
      for (var entry : all) Instant.parse(entry.endedAt());
    } catch (java.time.format.DateTimeParseException failure) {
      throw new IOException("O histórico contém uma data inválida.", failure);
    }
    var filtered =
        all.stream()
            .filter(entry -> kind.isEmpty() || entry.kind().equals(kind))
            .filter(entry -> outcome.isEmpty() || entry.completed() == outcome.equals("completed"))
            .filter(entry -> from == null || !Instant.parse(entry.endedAt()).isBefore(from))
            .filter(entry -> until == null || Instant.parse(entry.endedAt()).isBefore(until))
            .sorted(
                Comparator.comparing(
                        (PracticeWorkspace.Summary entry) -> Instant.parse(entry.endedAt()))
                    .reversed()
                    .thenComparing(PracticeWorkspace.Summary::id))
            .toList();
    int lastPage = Math.max(0, (filtered.size() - 1) / pageSize);
    int page = Math.min(requestedPage, lastPage);
    int start = page * pageSize;
    return new Page(
        List.copyOf(filtered.subList(start, Math.min(start + pageSize, filtered.size()))),
        page,
        pageSize,
        filtered.size(),
        all.size(),
        (int) filtered.stream().filter(PracticeWorkspace.Summary::completed).count());
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
    byte[] content = json.toJson(entries).getBytes(StandardCharsets.UTF_8);
    if (content.length > MAX_BYTES)
      throw new IOException("Practice history exceeds the 8 MiB limit");
    DurableFiles.replace(file, content);
    return List.copyOf(entries);
  }

  static PracticeHistory defaultHistory() {
    return new PracticeHistory(UserDirectories.data().resolve("practice-history.json"));
  }
}
