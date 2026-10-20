package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SourceRegistryTest {
  @Test
  void declaresOnlyImplementedCapabilitiesAndBlocksUnsupportedFormats() {
    var commons = SourceRegistry.byId("wikimedia-commons");
    assertEquals(commons, new WikimediaCommonsAdapter().descriptor());
    assertTrue(commons.capabilities().importByUrl());
    assertEquals(java.util.List.of("MIDI"), commons.capabilities().formats());
    assertEquals(
        commons,
        SourceRegistry.requireUrlImport(URI.create(WikimediaCommonsAdapterTest.PAGE), "MIDI"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SourceRegistry.requireUrlImport(URI.create(WikimediaCommonsAdapterTest.PAGE), "PDF"));
    for (var provider : SourceRegistry.all()) {
      assertFalse(provider.capabilities().search());
      assertFalse(provider.capabilities().selectParts());
      assertFalse(provider.capabilities().connectAccount());
      if (!provider.automationAllowed()) {
        assertFalse(provider.capabilities().importByUrl());
        assertTrue(provider.capabilities().formats().isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SourceRegistry.requireUrlImport(
                    URI.create("https://" + provider.host() + "/file"), "MIDI"));
      }
    }
    assertThrows(IllegalArgumentException.class, () -> SourceRegistry.byId("unknown"));
  }

  @Test
  void sharesProviderCooldownAcrossIndependentProfileImportWorkspaces() throws Exception {
    var calls = new AtomicInteger();
    byte[] midi = WikimediaCommonsAdapterTest.midi();
    ScoreSourceProvider provider =
        new WikimediaCommonsAdapter(
            (uri, agent, max) ->
                calls.incrementAndGet() == 1
                    ? WikimediaCommonsAdapterTest.json(
                        WikimediaCommonsAdapterTest.metadata(
                            midi, WikimediaCommonsAdapterTest.MEDIA, "audio/midi"))
                    : new WikimediaCommonsAdapter.Response(200, "audio/midi", midi, ""),
            () -> Instant.parse("2026-10-19T12:00:00Z"));
    new ImportWorkspace(provider)
        .previewWeb(WikimediaCommonsAdapterTest.PAGE, WikimediaCommonsAdapterTest.CONTACT);
    assertThrows(
        IllegalStateException.class,
        () ->
            new ImportWorkspace(provider)
                .previewWeb(WikimediaCommonsAdapterTest.PAGE, "other@example.org"));
    assertEquals(2, calls.get());
  }
}
