package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import org.junit.jupiter.api.Test;

class WikimediaCommonsAdapterTest {
  static final String PAGE = "https://commons.wikimedia.org/wiki/File:ARPEON_test.mid";
  static final String MEDIA = "https://upload.wikimedia.org/wikipedia/commons/a/ab/ARPEON_test.mid";
  static final String CONTACT = "arpeon@example.org";

  @Test
  void fetchesOnlyOfficialMetadataAndSelectedMidiAndRetainsRightsContext() throws Exception {
    byte[] midi = midi();
    var requests = new ArrayList<URI>();
    WikimediaCommonsAdapter.Fetcher fetcher =
        (uri, agent, max) -> {
          requests.add(uri);
          assertTrue(agent.startsWith("ARPEON/0.1 (" + CONTACT + ")"));
          return requests.size() == 1
              ? json(metadata(midi, MEDIA, "audio/midi"))
              : new WikimediaCommonsAdapter.Response(200, "audio/midi", midi, "");
        };
    var adapter = new WikimediaCommonsAdapter(fetcher, () -> Instant.parse("2026-10-08T12:00:00Z"));
    var result = adapter.preview(PAGE, CONTACT);
    assertEquals(2, requests.size());
    assertEquals("commons.wikimedia.org", requests.getFirst().getHost());
    assertEquals(URI.create(MEDIA), requests.get(1));
    assertEquals("EXACT_TICKS", result.draft().rhythm());
    assertEquals("wikimedia-commons", result.source().providerId());
    assertEquals(PAGE, result.source().pageUrl());
    assertEquals("CC0", result.source().licenseShortName());
    assertEquals("DECLARED_REVIEW_REQUIRED", result.source().licenseStatus());
    assertEquals("2026-10-08T12:00:00Z", result.source().fetchedAt());
    assertThrows(IllegalStateException.class, () -> adapter.preview(PAGE, CONTACT));
  }

  @Test
  void blocksUnreviewedAndManualSourcesBeforeNetwork() {
    var calls = new ArrayList<URI>();
    var adapter =
        new WikimediaCommonsAdapter(
            (uri, agent, max) -> {
              calls.add(uri);
              throw new AssertionError("A rejected URL must not be fetched");
            },
            Instant::now);
    for (String url :
        List.of(
            "https://musescore.com/user/123/score/456",
            "https://ciframelodica.com.br/mural/post/1/",
            "http://commons.wikimedia.org/wiki/File:ARPEON_test.mid",
            "https://commons.wikimedia.org:444/wiki/File:ARPEON_test.mid",
            "https://commons.wikimedia.org/wiki/File:ARPEON_test.mid?download=1",
            "https://127.0.0.1/wiki/File:ARPEON_test.mid",
            "https://commons.wikimedia.org/wiki/Special:Redirect/file/ARPEON_test.mid"))
      assertThrows(IllegalArgumentException.class, () -> adapter.preview(url, CONTACT), url);
    assertThrows(IllegalArgumentException.class, () -> adapter.preview(PAGE, "no contact"));
    assertTrue(calls.isEmpty());
    assertEquals(
        WikimediaCommonsAdapter.canonicalKey(PAGE),
        WikimediaCommonsAdapter.canonicalKey(
            "https://commons.wikimedia.org/wiki/File%3AARPEON%20test.mid"));
  }

  @Test
  void rejectsHostileMetadataAndChangedContent() throws Exception {
    byte[] midi = midi();
    var badHost =
        new WikimediaCommonsAdapter(
            (uri, agent, max) -> json(metadata(midi, "http://127.0.0.1/private.mid", "audio/midi")),
            Instant::now);
    assertThrows(IllegalArgumentException.class, () -> badHost.preview(PAGE, CONTACT));
    var calls = new ArrayList<URI>();
    var changed =
        new WikimediaCommonsAdapter(
            (uri, agent, max) -> {
              calls.add(uri);
              return calls.size() == 1
                  ? json(metadata(midi, MEDIA, "audio/midi"))
                  : new WikimediaCommonsAdapter.Response(200, "text/html", midi, "");
            },
            Instant::now);
    assertThrows(IllegalArgumentException.class, () -> changed.preview(PAGE, CONTACT));
    assertEquals(2, calls.size());
    var mismatchCalls = new ArrayList<URI>();
    var mismatch =
        new WikimediaCommonsAdapter(
            (uri, agent, max) -> {
              mismatchCalls.add(uri);
              return mismatchCalls.size() == 1
                  ? json(metadata(midi, MEDIA, "audio/midi"))
                  : new WikimediaCommonsAdapter.Response(
                      200, "audio/midi", new byte[] {'M', 'T', 'h', 'd'}, "");
            },
            Instant::now);
    assertThrows(IllegalArgumentException.class, () -> mismatch.preview(PAGE, CONTACT));
  }

  @Test
  void labelsMissingLicenseMetadataAsUnknownInsteadOfGrantingRights() throws Exception {
    byte[] midi = midi();
    var root =
        com.google.gson.JsonParser.parseString(metadata(midi, MEDIA, "audio/midi"))
            .getAsJsonObject();
    root.getAsJsonObject("query")
        .getAsJsonArray("pages")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("imageinfo")
        .get(0)
        .getAsJsonObject()
        .remove("extmetadata");
    var calls = new ArrayList<URI>();
    var adapter =
        new WikimediaCommonsAdapter(
            (uri, agent, max) -> {
              calls.add(uri);
              return calls.size() == 1
                  ? json(root.toString())
                  : new WikimediaCommonsAdapter.Response(200, "application/octet-stream", midi, "");
            },
            Instant::now);
    var source = adapter.preview(PAGE, CONTACT).source();
    assertEquals("UNKNOWN", source.licenseStatus());
    assertTrue(source.licenseUrl().isEmpty());
  }

  @Test
  void honorsProviderThrottlingWithoutRetrying() {
    var calls = new ArrayList<URI>();
    var adapter =
        new WikimediaCommonsAdapter(
            (uri, agent, max) -> {
              calls.add(uri);
              return new WikimediaCommonsAdapter.Response(
                  429, "application/json", new byte[0], "120");
            },
            Instant::now);
    assertThrows(IllegalStateException.class, () -> adapter.preview(PAGE, CONTACT));
    assertThrows(IllegalStateException.class, () -> adapter.preview(PAGE, CONTACT));
    assertEquals(1, calls.size());
    var lagCalls = new ArrayList<URI>();
    var lag =
        new WikimediaCommonsAdapter(
            (uri, agent, max) -> {
              lagCalls.add(uri);
              return json("{\"error\":{\"code\":\"maxlag\"}}");
            },
            Instant::now);
    assertThrows(IllegalStateException.class, () -> lag.preview(PAGE, CONTACT));
    assertThrows(IllegalStateException.class, () -> lag.preview(PAGE, CONTACT));
    assertEquals(1, lagCalls.size());
  }

  static WikimediaCommonsAdapter.Response json(String body) {
    return new WikimediaCommonsAdapter.Response(
        200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), "");
  }

  static String metadata(byte[] midi, String url, String mime) {
    String sha1;
    try {
      sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(midi));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
    JsonObject licenseName = new JsonObject();
    licenseName.addProperty("value", "CC0");
    JsonObject licenseUrl = new JsonObject();
    licenseUrl.addProperty("value", "https://creativecommons.org/publicdomain/zero/1.0/");
    JsonObject ext = new JsonObject();
    ext.add("LicenseShortName", licenseName);
    ext.add("LicenseUrl", licenseUrl);
    JsonObject info = new JsonObject();
    info.addProperty("size", midi.length);
    info.addProperty("mime", mime);
    info.addProperty("url", url);
    info.addProperty("sha1", sha1);
    info.add("extmetadata", ext);
    var infos = new com.google.gson.JsonArray();
    infos.add(info);
    JsonObject page = new JsonObject();
    page.addProperty("title", "File:ARPEON test.mid");
    page.add("imageinfo", infos);
    var pages = new com.google.gson.JsonArray();
    pages.add(page);
    JsonObject query = new JsonObject();
    query.add("pages", pages);
    JsonObject root = new JsonObject();
    root.add("query", query);
    return root.toString();
  }

  static byte[] midi() throws Exception {
    var sequence = new Sequence(Sequence.PPQ, 480);
    var track = sequence.createTrack();
    var on = new ShortMessage();
    on.setMessage(ShortMessage.NOTE_ON, 0, 60, 100);
    var off = new ShortMessage();
    off.setMessage(ShortMessage.NOTE_OFF, 0, 60, 0);
    track.add(new MidiEvent(on, 0));
    track.add(new MidiEvent(off, 480));
    var out = new ByteArrayOutputStream();
    MidiSystem.write(sequence, 0, out);
    return out.toByteArray();
  }
}
