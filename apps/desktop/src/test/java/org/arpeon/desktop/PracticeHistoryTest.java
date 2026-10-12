package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PracticeHistoryTest {
  @TempDir Path directory;

  private static PracticeWorkspace.Summary entry(
      int index, String kind, String endedAt, boolean completed) {
    return new PracticeWorkspace.Summary(
        "attempt-" + index,
        kind,
        "C_MAJOR",
        endedAt,
        endedAt,
        completed,
        8,
        completed ? 8 : 3,
        0,
        null,
        0,
        80);
  }

  private PracticeHistory history(List<PracticeWorkspace.Summary> entries) throws IOException {
    Path file = directory.resolve("history.json");
    Files.writeString(file, new Gson().toJson(entries));
    return new PracticeHistory(file);
  }

  @Test
  void emptyHistoryHasOneEmptyPage() throws IOException {
    var page =
        new PracticeHistory(directory.resolve("missing.json")).page(99, 20, "", "", null, null);
    assertEquals(0, page.page());
    assertEquals(0, page.totalCount());
    assertTrue(page.entries().isEmpty());
  }

  @Test
  void fiveThousandEntriesAreOrderedAndBoundedAtBothEnds() throws IOException {
    var entries = new ArrayList<PracticeWorkspace.Summary>();
    Instant base = Instant.parse("2026-10-01T12:00:00Z");
    for (int i = 0; i < 5000; i++)
      entries.add(entry(i, "NOTE", base.plusSeconds(i).toString(), i % 2 == 0));
    var history = history(entries);
    var first = history.page(0, 20, "", "", null, null);
    assertEquals(5000, first.totalCount());
    assertEquals(2500, first.completedCount());
    assertEquals(20, first.entries().size());
    assertEquals("attempt-4999", first.entries().getFirst().id());
    var last = history.page(Integer.MAX_VALUE, 20, "", "", null, null);
    assertEquals(249, last.page());
    assertEquals(20, last.entries().size());
    assertEquals("attempt-0", last.entries().getLast().id());
    assertEquals(
        first,
        new PracticeHistory(directory.resolve("history.json")).page(0, 20, "", "", null, null));
  }

  @Test
  void filtersUseInclusiveStartExclusiveEndAndCountTheWholeFilteredSet() throws IOException {
    var history =
        history(
            List.of(
                entry(1, "RHYTHM", "2026-10-10T03:00:00Z", false),
                entry(2, "RHYTHM", "2026-10-11T02:59:59Z", true),
                entry(3, "RHYTHM", "2026-10-11T03:00:00Z", true),
                entry(4, "NOTE", "2026-10-10T10:00:00Z", true)));
    var page =
        history.page(
            0,
            1,
            "RHYTHM",
            "",
            Instant.parse("2026-10-10T03:00:00Z"),
            Instant.parse("2026-10-11T03:00:00Z"));
    assertEquals(4, page.totalCount());
    assertEquals(2, page.matchingCount());
    assertEquals(1, page.completedCount());
    assertEquals("attempt-2", page.entries().getFirst().id());
    assertEquals(
        "attempt-1",
        history.page(0, 20, "RHYTHM", "interrupted", null, null).entries().getFirst().id());
  }

  @Test
  void duplicateTimestampsHaveStablePageBoundaries() throws IOException {
    var history =
        history(
            List.of(
                entry(2, "NOTE", "2026-10-10T12:00:00Z", true),
                entry(1, "NOTE", "2026-10-10T12:00:00Z", true)));
    assertEquals("attempt-1", history.page(0, 1, "", "", null, null).entries().getFirst().id());
    assertEquals("attempt-2", history.page(1, 1, "", "", null, null).entries().getFirst().id());
  }

  @Test
  void invalidQueriesAndCorruptedDatesDoNotLookLikeEmptyHistory() throws IOException {
    var history = history(List.of(entry(1, "NOTE", "bad-date", true)));
    assertThrows(IllegalArgumentException.class, () -> history.page(-1, 20, "", "", null, null));
    assertThrows(IllegalArgumentException.class, () -> history.page(0, 51, "", "", null, null));
    assertThrows(
        IllegalArgumentException.class, () -> history.page(0, 20, "OTHER", "", null, null));
    assertThrows(IllegalArgumentException.class, () -> history.page(0, 20, "", "bad", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> history.page(0, 20, "", "", Instant.EPOCH, Instant.EPOCH));
    assertThrows(IOException.class, () -> history.page(0, 20, "", "", null, null));
  }
}
