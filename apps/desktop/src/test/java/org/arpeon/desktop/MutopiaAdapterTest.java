package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MutopiaAdapterTest {
  @TempDir Path root;

  static byte[] archive() throws IOException {
    return Files.readAllBytes(
        Path.of(System.getProperty("arpeon.fixtures"))
            .getParent()
            .resolve("mutopia/bwv1013-mids.zip"));
  }

  static String selection() {
    return MutopiaCatalogue.entries().getFirst().id();
  }

  @Test
  void verifiesLicensedFixtureAndCreatesAReviewableDraftWithAnExactRetainedMember()
      throws Exception {
    var calls = new AtomicInteger();
    var clock = new AtomicReference<>(Instant.parse("2026-10-20T12:00:00Z"));
    var adapter =
        new MutopiaAdapter(
            uri -> {
              assertEquals(MutopiaCatalogue.ARCHIVE, uri.toString());
              calls.incrementAndGet();
              return new MutopiaAdapter.Response(200, archive(), "");
            },
            clock::get);
    var workspace = new ImportWorkspace(new WikimediaCommonsAdapter(), adapter);
    var preview = workspace.previewCatalogue("mutopia", selection());
    assertEquals(735, preview.draft().notes().size());
    assertEquals("EXACT_TICKS", preview.draft().rhythm());
    assertEquals(MutopiaCatalogue.entries().getFirst().sha256(), preview.draft().sha256());
    assertEquals("Public Domain", preview.source().licenseShortName());
    assertEquals("Johann Sebastian Bach", preview.source().metadata().composer());
    assertTrue(workspace.notation(preview.token(), 4, 480).contains("<score-partwise"));
    try (var score = new ScoreWorkspace()) {
      score.openDraft(
          preview.draft(), preview.source(), workspace.original(preview.token()), 4, 480);
      var data = score.projectData();
      assertEquals(
          47,
          new org.arpeon.musicxml.MusicXmlImporter()
              .read(
                  data.xml().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                  "converted.musicxml")
              .score()
              .parts()
              .getFirst()
              .measures()
              .size());
      assertEquals("MIDI", data.original().format());
      assertEquals("Johann Sebastian Bach", data.provenance().work().composer());
      assertEquals("flute", data.provenance().arrangement().parts().getFirst().instrumentId());
      assertEquals("NOT_REVIEWED", data.provenance().rights().status());
      assertEquals("UNKNOWN", data.provenance().rights().redistribution());
      var store = new ProjectStore(root);
      var saved = store.save(null, data);
      var copy = store.duplicate(saved.id(), "Cópia Mutopia");
      assertArrayEquals(
          data.original().copyBytes(), new ProjectStore(root).original(copy.id()).copyBytes());
      assertEquals(data.source(), new ProjectStore(root).load(saved.id()).data().source());
    }
    workspace.clear();
    assertThrows(IllegalStateException.class, () -> workspace.original(preview.token()));
    workspace.previewCatalogue("mutopia", selection());
    assertEquals(1, calls.get(), "Reopening a public selection uses the verified bounded cache");
    clock.set(clock.get().plusSeconds(601));
    workspace.previewCatalogue("mutopia", selection());
    assertEquals(2, calls.get());
  }

  @Test
  void rejectsUnreviewedSelectionsBeforeNetworkAndNeverCachesChangedOrOversizedPackages()
      throws Exception {
    var calls = new AtomicInteger();
    var clock = new AtomicReference<>(Instant.parse("2026-10-20T12:00:00Z"));
    byte[] altered = archive();
    altered[40] ^= 1;
    var adapter =
        new MutopiaAdapter(
            uri -> {
              calls.incrementAndGet();
              return new MutopiaAdapter.Response(200, altered, "");
            },
            clock::get);
    for (String input :
        java.util.List.of("../../secret", "https://example.org/file", "bwv1013-1.mid", ""))
      assertThrows(IllegalArgumentException.class, () -> adapter.preview(input, ""));
    assertEquals(0, calls.get());
    assertThrows(IOException.class, () -> adapter.preview(selection(), ""));
    assertThrows(IllegalStateException.class, () -> adapter.preview(selection(), ""));
    clock.set(clock.get().plusSeconds(6));
    assertThrows(IOException.class, () -> adapter.preview(selection(), ""));
    assertEquals(2, calls.get());
    var oversized =
        new MutopiaAdapter(
            uri -> new MutopiaAdapter.Response(200, new byte[MutopiaAdapter.MAX_BYTES + 1], ""),
            clock::get);
    assertThrows(IOException.class, () -> oversized.preview(selection(), ""));
    for (int status : java.util.List.of(301, 302, 403, 404, 500)) {
      var failed =
          new MutopiaAdapter(
              uri -> new MutopiaAdapter.Response(status, new byte[0], ""), clock::get);
      assertThrows(IOException.class, () -> failed.preview(selection(), ""));
    }
  }

  @Test
  void respectsRetryAfterAcrossProfilesWithoutAutomaticRetries() throws Exception {
    for (String header : java.util.List.of("7200", "Tue, 20 Oct 2026 14:00:00 GMT")) {
      var calls = new AtomicInteger();
      var clock = new AtomicReference<>(Instant.parse("2026-10-20T12:00:00Z"));
      var adapter =
          new MutopiaAdapter(
              uri -> {
                calls.incrementAndGet();
                return new MutopiaAdapter.Response(429, new byte[0], header);
              },
              clock::get);
      assertThrows(
          IllegalStateException.class,
          () ->
              new ImportWorkspace(new WikimediaCommonsAdapter(), adapter)
                  .previewCatalogue("mutopia", selection()));
      clock.set(clock.get().plusSeconds(3601));
      assertThrows(
          IllegalStateException.class,
          () ->
              new ImportWorkspace(new WikimediaCommonsAdapter(), adapter)
                  .previewCatalogue("mutopia", selection()));
      assertEquals(1, calls.get());
      clock.set(clock.get().plusSeconds(3600));
      assertThrows(IllegalStateException.class, () -> adapter.preview(selection(), ""));
      assertEquals(2, calls.get());
    }
  }
}
