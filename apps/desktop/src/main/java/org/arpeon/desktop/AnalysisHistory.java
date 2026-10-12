package org.arpeon.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.arpeon.analysis.PerformanceAnalyzer;

/** Bounded local report history. It stores analysis results and provenance, never audio samples. */
final class AnalysisHistory {
  private static final long MAX_BYTES = 16L * 1024 * 1024;
  private static final int MAX_ENTRIES = 100;

  record Entry(
      String id,
      String projectId,
      String sha256,
      int revision,
      String title,
      String filename,
      String partId,
      String partName,
      String mode,
      String completedAt,
      PerformanceAnalyzer.Report report,
      AnalysisStudy.Context study) {}

  record Summary(
      String id,
      String projectId,
      String sha256,
      int revision,
      String title,
      String filename,
      String partName,
      String mode,
      String completedAt,
      String reportStatus,
      int expectedCount,
      int matchedCount,
      Integer missedCount,
      Integer extraCount,
      Double pitchAccuracyPercent,
      Double timingAccuracyPercent,
      Double durationAccuracyPercent,
      Double interOnsetAccuracyPercent) {}

  private final Path file;
  private final Gson json = new Gson();

  AnalysisHistory(Path file) {
    this.file = file.toAbsolutePath().normalize();
  }

  static AnalysisHistory defaultHistory() {
    return new AnalysisHistory(UserDirectories.data().resolve("analysis-history.json"));
  }

  synchronized List<Summary> summaries() throws IOException {
    return list().stream().map(AnalysisHistory::summary).toList();
  }

  synchronized Entry get(String id) throws IOException {
    return list().stream()
        .filter(entry -> entry.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Analysis report not found"));
  }

  synchronized Entry add(Entry entry) throws IOException {
    validate(entry);
    var entries = new ArrayList<>(list());
    entries.removeIf(previous -> previous.id().equals(entry.id()));
    entries.add(0, entry);
    if (entries.size() > MAX_ENTRIES) entries.subList(MAX_ENTRIES, entries.size()).clear();
    write(entries);
    return entry;
  }

  synchronized void delete(String id) throws IOException {
    UUID.fromString(id);
    var entries = new ArrayList<>(list());
    if (!entries.removeIf(entry -> entry.id().equals(id)))
      throw new IllegalArgumentException("Analysis report not found");
    write(entries);
  }

  synchronized void deleteProject(String projectId) throws IOException {
    var entries = new ArrayList<>(list());
    if (entries.removeIf(entry -> projectId.equals(entry.projectId()))) write(entries);
  }

  private List<Entry> list() throws IOException {
    if (!Files.exists(file)) return List.of();
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > MAX_BYTES)
      throw new IOException("Analysis history is missing or too large");
    try {
      Entry[] entries =
          json.fromJson(Files.readString(file, StandardCharsets.UTF_8), Entry[].class);
      if (entries == null || entries.length > MAX_ENTRIES)
        throw new IOException("Invalid analysis history");
      for (Entry entry : entries) validate(entry);
      return List.of(entries);
    } catch (JsonParseException | IllegalStateException failure) {
      throw new IOException("Invalid analysis history", failure);
    }
  }

  private void write(List<Entry> entries) throws IOException {
    var retained = new ArrayList<>(entries);
    if (retained.size() > MAX_ENTRIES) retained.subList(MAX_ENTRIES, retained.size()).clear();
    byte[] content = json.toJson(retained).getBytes(StandardCharsets.UTF_8);
    while (content.length > MAX_BYTES && retained.size() > 1) {
      retained.removeLast();
      content = json.toJson(retained).getBytes(StandardCharsets.UTF_8);
    }
    if (content.length > MAX_BYTES)
      throw new IOException("Analysis history exceeds the 16 MiB limit");
    DurableFiles.replace(file, content);
  }

  private static Summary summary(Entry entry) {
    var report = entry.report();
    return new Summary(
        entry.id(),
        entry.projectId(),
        entry.sha256(),
        entry.revision(),
        entry.title(),
        entry.filename(),
        entry.partName(),
        entry.mode(),
        entry.completedAt(),
        report.status(),
        report.expectedCount(),
        report.matchedCount(),
        report.missedCount(),
        report.extraCount(),
        report.pitchAccuracyPercent(),
        report.timingAccuracyPercent(),
        report.durationAccuracyPercent(),
        report.interOnsetAccuracyPercent());
  }

  private static void validate(Entry entry) throws IOException {
    try {
      if (entry == null
          || entry.id() == null
          || !UUID.fromString(entry.id()).toString().equals(entry.id())
          || entry.projectId() != null
              && !UUID.fromString(entry.projectId()).toString().equals(entry.projectId())
          || entry.sha256() == null
          || !entry.sha256().matches("[0-9a-f]{64}")
          || entry.revision() < 0
          || entry.title() == null
          || entry.filename() == null
          || entry.partId() == null
          || entry.partName() == null
          || !"ANALYSIS".equals(entry.mode())
          || entry.completedAt() == null
          || entry.report() == null
          || entry.report().expectedCount() < 1
          || entry.report().expectedCount() > PerformanceAnalyzer.MAX_EXPECTED_EVENTS
          || entry.report().observedCount() > PerformanceAnalyzer.MAX_OBSERVED_EVENTS
          || entry.report().matches() == null
          || entry.report().matches().size() > PerformanceAnalyzer.MAX_EXPECTED_EVENTS
          || entry.report().missedIndices() == null
          || entry.report().missedIndices().size() > PerformanceAnalyzer.MAX_EXPECTED_EVENTS
          || entry.report().extraIndices() == null
          || entry.report().extraIndices().size() > PerformanceAnalyzer.MAX_OBSERVED_EVENTS)
        throw new IOException("Invalid analysis report");
      Instant.parse(entry.completedAt());
      var study = entry.study();
      if (study != null) {
        if (study.scoreXmlSha256() == null
            || !study.scoreXmlSha256().matches("[0-9a-f]{64}")
            || !Double.isFinite(study.initialBpm())
            || study.initialBpm() <= 0
            || study.config() == null
            || study.notes() == null
            || study.notes().size() != entry.report().expectedCount())
          throw new IOException("Invalid analysis study context");
        for (int i = 0; i < study.notes().size(); i++) {
          var note = study.notes().get(i);
          if (note == null
              || note.index() != i
              || note.measureIndex() < 0
              || note.eventIndex() < 0
              || note.measureNumber() == null
              || note.executionMeasureIndex() < 0
              || note.endMeasureIndex() < note.executionMeasureIndex())
            throw new IOException("Invalid analysis study location");
        }
      }
    } catch (IllegalArgumentException
        | NullPointerException
        | java.time.DateTimeException failure) {
      throw new IOException("Invalid analysis report", failure);
    }
  }
}
