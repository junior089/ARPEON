package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.arpeon.audio.AudioFrame;
import org.arpeon.audio.AudioInputDevice;
import org.arpeon.audio.AudioInputPort;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.arpeon.musicxml.MusicXmlImporter;
import org.arpeon.omr.AudiverisOmrEngine;
import org.arpeon.omr.OmrResult;
import org.arpeon.tuner.TunerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopServerTest {
  @Test
  void updatePreferencesRejectMalformedRequestsAndInactivePackages() throws Exception {
    var status = JsonParser.parseString(get("/api/updates").body()).getAsJsonObject();
    assertFalse(status.get("enabled").getAsBoolean());
    assertFalse(status.get("automaticChecking").getAsBoolean());
    for (String request :
        List.of(
            "{}",
            "{\"automaticChecking\":\"true\"}",
            "{\"automaticChecking\":true,\"endpoint\":\"https://untrusted\"}")) {
      var response =
          client.send(
              request("/api/updates/preferences")
                  .header("Content-Type", "application/json")
                  .PUT(HttpRequest.BodyPublishers.ofString(request))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode());
    }
    var response =
        client.send(
            request("/api/updates/preferences")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"automaticChecking\":true}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(409, response.statusCode());
  }

  @Test
  void draftNotationIsTheAcceptedScoreAndDoesNotChangeTheOpenDocument() throws Exception {
    byte[] source =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml"));
    assertEquals(
        200,
        client
            .send(
                request("/api/score/open")
                    .header("Content-Type", "application/xml")
                    .header("X-Arpeon-Filename", "first-notes.musicxml")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(source))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    String original = get("/api/score/export").body();
    var preview =
        client.send(
            request("/api/import/preview")
                .header("Content-Type", "application/octet-stream")
                .header("X-Arpeon-Filename", "notes.txt")
                .header("X-Arpeon-Import-Kind", "NOTES")
                .POST(HttpRequest.BodyPublishers.ofString("C4 D#4 A4"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, preview.statusCode());
    String token =
        JsonParser.parseString(preview.body()).getAsJsonObject().get("token").getAsString();
    String parameters = "{\"token\":\"" + token + "\",\"fallbackOctave\":4,\"equalNoteTicks\":480";
    var notation = post("/api/import/notation", parameters + "}");
    assertEquals(200, notation.statusCode());
    String xml = JsonParser.parseString(notation.body()).getAsJsonObject().get("xml").getAsString();
    assertTrue(xml.contains("<step>D</step><alter>1</alter>"));
    assertEquals(original, get("/api/score/export").body());
    assertEquals(
        200, post("/api/import/accept", parameters + ",\"acknowledge\":true}").statusCode());
    assertEquals(xml, get("/api/score/export").body());
  }

  @Test
  void servesVectorBrandAssetsWithTheirMediaType() throws Exception {
    var logo = get("/brand/arpeon-logo-horizontal-dark.svg");
    assertEquals(200, logo.statusCode());
    assertEquals("image/svg+xml", logo.headers().firstValue("Content-Type").orElseThrow());
    assertTrue(logo.body().contains("<svg"));
    assertEquals(200, get("/brand/arpeon-256.png").statusCode());
  }

  @TempDir Path temporary;
  private DesktopServer server;
  private HttpClient client;
  private String origin;
  private String token;
  private TestInput input;

  @Test
  void practiceHistoryPagesValidateQueriesAndRequireAuthentication() throws Exception {
    var page = get("/api/practice/history?page=0&pageSize=20");
    assertEquals(200, page.statusCode());
    var result = JsonParser.parseString(page.body()).getAsJsonObject();
    assertEquals(20, result.get("pageSize").getAsInt());
    assertEquals(0, result.getAsJsonArray("entries").size());
    for (var query :
        List.of(
            "page=-1",
            "pageSize=5000",
            "kind=BAD",
            "from=bad",
            "until=bad",
            "page=0&page=1",
            "unknown=1"))
      assertEquals(400, get("/api/practice/history?" + query).statusCode(), query);
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(origin + "/api/practice/history")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void unexpectedOmrFailureReturnsJsonAndKeepsLocalServerAlive() throws Exception {
    var image = new BufferedImage(120, 120, BufferedImage.TYPE_BYTE_GRAY);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    try (var http = HttpClient.newHttpClient();
        var webServer =
            new DesktopServer(
                new TunerService(new TestInput(), new YinPitchDetector()),
                new PracticeHistory(temporary.resolve("omr-failure-history.json")),
                new ImportWorkspace(),
                new OmrWorkspace(
                    source -> {
                      throw new NullPointerException("simulated recognition failure");
                    }))) {
      webServer.start();
      URI launch = webServer.launchUri();
      String local = "http://127.0.0.1:" + launch.getPort();
      String secret = launch.getFragment().substring("session=".length());
      var preview =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/omr/preview"))
                  .header("X-Arpeon-Session", secret)
                  .header("X-Arpeon-Filename", "page.png")
                  .header("Content-Type", "application/octet-stream")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(500, preview.statusCode());
      assertTrue(preview.body().contains("Falha inesperada no reconhecimento"));
      var status =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/omr/status"))
                  .header("X-Arpeon-Session", secret)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, status.statusCode());
    }
  }

  @Test
  void omrRequiresReviewBeforeOpeningRecognizedMusicXml() throws Exception {
    var image = new BufferedImage(120, 120, BufferedImage.TYPE_BYTE_GRAY);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    var fixture =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml"));
    var imported =
        new MusicXmlImporter().read(new ByteArrayInputStream(fixture), "recognized.musicxml");
    try (var http = HttpClient.newHttpClient();
        var webServer =
            new DesktopServer(
                new TunerService(new TestInput(), new YinPitchDetector()),
                new PracticeHistory(temporary.resolve("omr-history.json")),
                new ImportWorkspace(),
                new OmrWorkspace(
                    source ->
                        new OmrResult(
                            imported,
                            "a".repeat(64),
                            source.filename(),
                            1,
                            List.of("Revise o reconhecimento."),
                            List.of(1),
                            List.of())))) {
      webServer.start();
      URI launch = webServer.launchUri();
      String local = "http://127.0.0.1:" + launch.getPort();
      String secret = launch.getFragment().substring("session=".length());
      var preview =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/omr/preview"))
                  .header("X-Arpeon-Session", secret)
                  .header("X-Arpeon-Filename", "page.png")
                  .header("Content-Type", "application/octet-stream")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, preview.statusCode());
      var review = JsonParser.parseString(preview.body()).getAsJsonObject();
      assertEquals(1, review.get("pages").getAsInt());
      assertEquals(1, review.getAsJsonArray("pagesRequiringReview").size());
      assertTrue(review.getAsJsonArray("recognizedMeasures").size() >= 1);
      assertTrue(review.getAsJsonArray("measuresRequiringReview").size() >= 1);
      String previewToken = review.get("token").getAsString();
      var rejected =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/omr/accept"))
                  .header("X-Arpeon-Session", secret)
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"token\":\"" + previewToken + "\",\"acknowledge\":false}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, rejected.statusCode());
      var accepted =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/omr/accept"))
                  .header("X-Arpeon-Session", secret)
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"token\":\"" + previewToken + "\",\"acknowledge\":true}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());
      var score =
          JsonParser.parseString(accepted.body()).getAsJsonObject().getAsJsonObject("score");
      assertEquals("OMR", score.get("sourceKind").getAsString());
      assertEquals("a".repeat(64), score.get("sha256").getAsString());
      assertTrue(score.getAsJsonArray("importWarnings").toString().contains("Revise"));
    }
  }

  @Test
  void acceptedCommonsUrlFlowsThroughHttpPreviewToScoreWithProvenance() throws Exception {
    byte[] midi = WikimediaCommonsAdapterTest.midi();
    var outbound = new java.util.ArrayList<URI>();
    var adapter =
        new WikimediaCommonsAdapter(
            (uri, agent, max) -> {
              outbound.add(uri);
              return outbound.size() == 1
                  ? WikimediaCommonsAdapterTest.json(
                      WikimediaCommonsAdapterTest.metadata(
                          midi, WikimediaCommonsAdapterTest.MEDIA, "audio/midi"))
                  : new WikimediaCommonsAdapter.Response(200, "audio/midi", midi, "");
            },
            () -> Instant.parse("2026-10-08T12:00:00Z"));
    try (var http = HttpClient.newHttpClient();
        var webServer =
            new DesktopServer(
                new TunerService(new TestInput(), new YinPitchDetector()),
                new PracticeHistory(temporary.resolve("web-history.json")),
                new ImportWorkspace(adapter))) {
      webServer.start();
      URI launch = webServer.launchUri();
      String local = "http://127.0.0.1:" + launch.getPort();
      String secret = launch.getFragment().substring("session=".length());
      String requestBody =
          "{\"url\":\""
              + WikimediaCommonsAdapterTest.PAGE
              + "\",\"contact\":\""
              + WikimediaCommonsAdapterTest.CONTACT
              + "\"}";
      var preview =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/import/url"))
                  .header("X-Arpeon-Session", secret)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, preview.statusCode());
      var parsed = JsonParser.parseString(preview.body()).getAsJsonObject();
      assertEquals("CC0", parsed.getAsJsonObject("source").get("licenseShortName").getAsString());
      String draftToken = parsed.get("token").getAsString();
      var accepted =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/import/accept"))
                  .header("X-Arpeon-Session", secret)
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"token\":\""
                              + draftToken
                              + "\",\"fallbackOctave\":4,\"equalNoteTicks\":480,\"acknowledge\":true}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());
      var score =
          JsonParser.parseString(accepted.body()).getAsJsonObject().getAsJsonObject("score");
      assertEquals(
          WikimediaCommonsAdapterTest.PAGE,
          score.getAsJsonObject("source").get("pageUrl").getAsString());
      assertEquals("MIDI", score.get("sourceKind").getAsString());
      assertTrue(score.getAsJsonArray("importWarnings").size() >= 3);
      assertTrue(score.getAsJsonArray("importWarnings").toString().contains("Processamento:"));
      var duplicate =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/import/url"))
                  .header("X-Arpeon-Session", secret)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, duplicate.statusCode());
      assertEquals(2, outbound.size());
    }
  }

  @Test
  void providerRegistryAndUrlImportRejectUnapprovedHostsBeforeFetching() throws Exception {
    var registry = get("/api/import/providers");
    assertEquals(200, registry.statusCode());
    assertTrue(registry.body().contains("wikimedia-commons"));
    assertTrue(registry.body().contains("musescore"));
    assertTrue(registry.body().contains("cifra-melodica"));
    assertEquals(
        400,
        post(
                "/api/import/url",
                "{\"url\":\"https://musescore.com/user/1/score/2\",\"contact\":\"user@example.org\"}")
            .statusCode());
    assertEquals(
        400,
        post(
                "/api/import/url",
                "{\"url\":\"https://127.0.0.1/private.mid\",\"contact\":\"user@example.org\"}")
            .statusCode());
    assertEquals(400, post("/api/import/url", "{\"url\":\"x\"}").statusCode());
  }

  @Test
  void importPreviewRequiresReviewAndKeepsSourceProvenance() throws Exception {
    var missingKind =
        client.send(
            request("/api/import/preview")
                .header("Content-Type", "application/octet-stream")
                .header("X-Arpeon-Filename", "pasted.txt")
                .POST(HttpRequest.BodyPublishers.ofString("DO RE"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, missingKind.statusCode());
    var denied =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/import/preview"))
                .header("Content-Type", "application/octet-stream")
                .header("X-Arpeon-Filename", "pasted.txt")
                .header("X-Arpeon-Import-Kind", "CIFRA_MELODICA")
                .POST(HttpRequest.BodyPublishers.ofString("DO RE MI FA"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, denied.statusCode());
    var preview =
        client.send(
            request("/api/import/preview")
                .header("Content-Type", "application/octet-stream")
                .header("X-Arpeon-Filename", "pasted.txt")
                .header("X-Arpeon-Import-Kind", "CIFRA_MELODICA")
                .POST(HttpRequest.BodyPublishers.ofString("DO RE MI FA"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, preview.statusCode());
    var parsed = JsonParser.parseString(preview.body()).getAsJsonObject();
    String draftToken = parsed.get("token").getAsString();
    String originalHash = parsed.getAsJsonObject("draft").get("sha256").getAsString();
    assertEquals("NONE", parsed.getAsJsonObject("draft").get("rhythm").getAsString());
    assertEquals(409, get("/api/score").statusCode());
    assertEquals(
        400,
        post(
                "/api/import/accept",
                "{\"token\":\""
                    + draftToken
                    + "\",\"fallbackOctave\":4,\"equalNoteTicks\":480,\"acknowledge\":false}")
            .statusCode());
    assertEquals(
        409,
        post(
                "/api/import/accept",
                "{\"token\":\"stale\",\"fallbackOctave\":4,\"equalNoteTicks\":480,\"acknowledge\":true}")
            .statusCode());
    var accepted =
        post(
            "/api/import/accept",
            "{\"token\":\""
                + draftToken
                + "\",\"fallbackOctave\":4,\"equalNoteTicks\":480,\"acknowledge\":true}");
    assertEquals(200, accepted.statusCode());
    assertEquals("chunked", accepted.headers().firstValue("Transfer-Encoding").orElse(""));
    var score = JsonParser.parseString(accepted.body()).getAsJsonObject().getAsJsonObject("score");
    assertEquals(originalHash, score.get("sha256").getAsString());
    assertEquals("CIFRA_MELODICA", score.get("sourceKind").getAsString());
    assertTrue(score.getAsJsonArray("importWarnings").size() >= 3);
    assertEquals(
        409,
        post(
                "/api/import/accept",
                "{\"token\":\""
                    + draftToken
                    + "\",\"fallbackOctave\":4,\"equalNoteTicks\":480,\"acknowledge\":true}")
            .statusCode());
  }

  @BeforeEach
  void start() throws Exception {
    input = new TestInput();
    server =
        new DesktopServer(
            new TunerService(input, new YinPitchDetector()),
            new PracticeHistory(temporary.resolve("history.json")),
            new ImportWorkspace(),
            new OmrWorkspace(new AudiverisOmrEngine()),
            new ProjectStore(temporary.resolve("projects")),
            new PreferencesStore(temporary.resolve("preferences.json")),
            new AudioPreferencesStore(temporary.resolve("audio-preferences.json")));
    server.start();
    var uri = server.launchUri();
    origin = "http://127.0.0.1:" + uri.getPort();
    token = uri.getFragment().substring("session=".length());
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  @Test
  void protectsMicrophoneFromUnauthorizedAndCrossOriginRequests() throws Exception {
    var denied =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/devices")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, denied.statusCode());
    var foreign =
        client.send(
            request("/api/devices").header("Origin", "https://example.invalid").GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, foreign.statusCode());
    assertFalse(input.open);
    assertEquals(
        400,
        post("/api/tuner/start", "{\"deviceId\":\"test\",\"referenceHz\":440,\"consent\":false}")
            .statusCode());
    assertEquals(413, post("/api/tuner/start", " ".repeat(1025)).statusCode());
    assertFalse(input.open);
  }

  @Test
  void preferencesAreAuthenticatedValidatedAndRestoredAfterServerRestart() throws Exception {
    var denied =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/preferences")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, denied.statusCode());
    var foreign =
        client.send(
            request("/api/preferences").header("Origin", "https://example.invalid").GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, foreign.statusCode());
    assertEquals("null", get("/api/preferences").body());
    var saved =
        client.send(
            request("/api/preferences")
                .header("Content-Type", "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(PreferencesStoreTest.settings().toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, saved.statusCode());
    var invalid =
        client.send(
            request("/api/preferences")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"zoom\":999}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, invalid.statusCode());
    server.close();
    server =
        new DesktopServer(
            new TunerService(input, new YinPitchDetector()),
            new PracticeHistory(temporary.resolve("history.json")),
            new ImportWorkspace(),
            new OmrWorkspace(new AudiverisOmrEngine()),
            new ProjectStore(temporary.resolve("projects")),
            new PreferencesStore(temporary.resolve("preferences.json")),
            new AudioPreferencesStore(temporary.resolve("audio-preferences.json")));
    server.start();
    var launch = server.launchUri();
    origin = "http://127.0.0.1:" + launch.getPort();
    token = launch.getFragment().substring("session=".length());
    assertEquals(
        JsonParser.parseString(saved.body()),
        JsonParser.parseString(get("/api/preferences").body()));
    assertFalse(input.open);
  }

  @Test
  void audioPreferencesRequireSessionAndDoNotRestoreConsent() throws Exception {
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(origin + "/api/audio/preferences")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        403,
        client
            .send(
                request("/api/audio/preferences")
                    .header("Origin", "https://example.invalid")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals("null", get("/api/audio/preferences").body());
    var saved =
        client.send(
            request("/api/audio/preferences")
                .header("Content-Type", "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        AudioPreferencesStoreTest.settings().toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, saved.statusCode());
    assertEquals(
        JsonParser.parseString(saved.body()),
        JsonParser.parseString(get("/api/audio/preferences").body()));
    assertFalse(input.open);
    var invalid = AudioPreferencesStoreTest.settings();
    invalid.addProperty("consent", true);
    assertEquals(
        400,
        client
            .send(
                request("/api/audio/preferences")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(invalid.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertFalse(input.open);
    Files.writeString(temporary.resolve("audio-preferences.json"), "{broken");
    assertEquals(500, get("/api/audio/preferences").statusCode());
  }

  @Test
  void scoreSnapshotsDeferEventsUntilTheRequestedMeasureIsLoaded() throws Exception {
    assertTrue(server.canUpdate());
    byte[] source =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml"));
    var opened =
        client.send(
            request("/api/score/open")
                .header("Content-Type", "application/xml")
                .header("X-Arpeon-Filename", "first-notes.musicxml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(source))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, opened.statusCode());
    assertFalse(server.canUpdate());
    assertEquals(200, post("/api/projects", "{}").statusCode());
    assertTrue(server.canUpdate());
    assertEquals(
        200,
        post(
                "/api/score/edit/set-pitch",
                "{\"partId\":\"P1\",\"measureIndex\":0,\"voiceId\":\"1\",\"eventIndex\":0,\"step\":\"D\",\"alter\":0,\"octave\":4}")
            .statusCode());
    assertFalse(server.canUpdate());
    var snapshot = JsonParser.parseString(get("/api/score").body()).getAsJsonObject();
    var firstMeasure =
        snapshot
            .getAsJsonObject("score")
            .getAsJsonArray("parts")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("measures")
            .get(0)
            .getAsJsonObject();
    assertEquals(0, firstMeasure.getAsJsonArray("events").size());

    var loaded =
        JsonParser.parseString(get("/api/score/measure-events?partId=P1&measureIndex=0").body())
            .getAsJsonObject();
    assertEquals(1, loaded.get("revision").getAsInt());
    assertEquals("P1", loaded.get("partId").getAsString());
    assertEquals(0, loaded.get("index").getAsInt());
    assertTrue(loaded.getAsJsonArray("events").size() > 0);
    assertEquals(1, loaded.get("durationNumerator").getAsInt());
    assertEquals(1, loaded.get("durationDenominator").getAsInt());
    assertEquals(400, get("/api/score/measure-events?partId=P1&measureIndex=-1").statusCode());
  }

  @Test
  void autosaveRunsOnItsDedicatedWriterWhileManualSaveUsesRequestWorker() throws Exception {
    byte[] source =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml"));
    var writerThread = new AtomicReference<String>();
    var projects =
        new ProjectStore(
            temporary.resolve("autosave-projects"),
            (path, content) -> {
              writerThread.set(Thread.currentThread().getName());
              Files.write(path, content);
            });
    try (var http = HttpClient.newHttpClient();
        var webServer =
            new DesktopServer(
                new TunerService(new TestInput(), new YinPitchDetector()),
                new PracticeHistory(temporary.resolve("autosave-history.json")),
                new ImportWorkspace(),
                new OmrWorkspace(sourceImage -> null),
                projects)) {
      webServer.start();
      URI launch = webServer.launchUri();
      String local = "http://127.0.0.1:" + launch.getPort();
      String secret = launch.getFragment().substring("session=".length());
      var opened =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/score/open"))
                  .header("X-Arpeon-Session", secret)
                  .header("Content-Type", "application/xml")
                  .header("X-Arpeon-Filename", "first-notes.musicxml")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(source))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, opened.statusCode());

      var autosaved =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/projects"))
                  .header("X-Arpeon-Session", secret)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{\"autosave\":true}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, autosaved.statusCode());
      String projectId =
          JsonParser.parseString(autosaved.body()).getAsJsonObject().get("id").getAsString();
      assertEquals("arpeon-project-autosave", writerThread.get());

      var manual =
          http.send(
              HttpRequest.newBuilder(URI.create(local + "/api/projects"))
                  .header("X-Arpeon-Session", secret)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + projectId + "\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, manual.statusCode());
      assertEquals(
          projectId,
          JsonParser.parseString(manual.body()).getAsJsonObject().get("id").getAsString());
      assertFalse(writerThread.get().equals("arpeon-project-autosave"));
      assertTrue(projects.load(projectId).data().xml().contains("<score-partwise"));
    }
  }

  @Test
  void analysisRequiresConsentAndScoreThenStopsCaptureOnCancel() throws Exception {
    String settings =
        "{\"partId\":\"P1\",\"deviceId\":\"test\",\"referenceHz\":440,"
            + "\"consent\":true,\"alignmentWindowMs\":450,\"timingToleranceMs\":120,"
            + "\"pitchToleranceCents\":50,\"rhythmTolerancePercent\":20,"
            + "\"latencyCompensationMs\":0}";
    assertEquals(409, post("/api/analysis/start", settings).statusCode());
    byte[] source =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml"));
    assertEquals(
        200,
        client
            .send(
                request("/api/score/open")
                    .header("Content-Type", "application/xml")
                    .header("X-Arpeon-Filename", "first-notes.musicxml")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(source))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        400,
        post("/api/analysis/start", settings.replace("\"consent\":true", "\"consent\":false"))
            .statusCode());
    assertFalse(input.open);
    var started = post("/api/analysis/start", settings);
    assertEquals(200, started.statusCode());
    assertTrue(input.open);
    var view = JsonParser.parseString(started.body()).getAsJsonObject();
    assertEquals("COUNT_IN", view.get("status").getAsString());
    assertTrue(view.get("expectedCount").getAsInt() > 0);
    assertEquals(200, get("/api/analysis/observe").statusCode());
    assertEquals(200, post("/api/analysis/stop", "{}").statusCode());
    assertFalse(input.open);
    assertEquals(
        "STOPPED",
        JsonParser.parseString(get("/api/analysis").body())
            .getAsJsonObject()
            .get("status")
            .getAsString());
    var following =
        post(
            "/api/analysis/start",
            settings.replace(
                "\"latencyCompensationMs\":0}",
                "\"latencyCompensationMs\":0,\"mode\":\"FOLLOW\"}"));
    assertEquals(200, following.statusCode());
    var followView = JsonParser.parseString(following.body()).getAsJsonObject();
    assertEquals("FOLLOW", followView.get("mode").getAsString());
    assertEquals(-1, followView.getAsJsonObject("follow").get("noteIndex").getAsInt());
    assertEquals("UNCERTAIN", followView.getAsJsonObject("follow").get("tempoState").getAsString());
    assertEquals(
        followView.get("expectedCount").getAsInt(), followView.getAsJsonArray("expected").size());
    assertEquals(
        followView.get("expectedCount").getAsInt(),
        followView.getAsJsonArray("expectedTicks").size());
    assertEquals(200, post("/api/analysis/finish", "{}").statusCode());
    assertFalse(input.open);
  }

  @Test
  void realDspCrossesHttpContractAndStops() throws Exception {
    assertEquals(200, get("/api/devices").statusCode());
    assertEquals(
        200,
        post("/api/tuner/start", "{\"deviceId\":\"test\",\"referenceHz\":440,\"consent\":true}")
            .statusCode());
    for (int i = 0; i < 3; i++) {
      var time = Instant.now();
      float[] samples = new float[4096];
      for (int j = 0; j < samples.length; j++)
        samples[j] = (float) (.5 * Math.sin(2 * Math.PI * 440 * j / 48000));
      input.frames.accept(new AudioFrame(samples, 48000, time, i));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < deadline) {
        var body = JsonParser.parseString(get("/api/tuner").body()).getAsJsonObject();
        if (!body.get("capturedAt").isJsonNull()
            && body.get("capturedAt").getAsString().equals(time.toString())) break;
        TimeUnit.MILLISECONDS.sleep(10);
      }
    }
    var observed = JsonParser.parseString(get("/api/tuner").body()).getAsJsonObject();
    assertEquals("TRACKING", observed.get("state").getAsString());
    assertEquals("A4", observed.get("nearestPitch").getAsString());
    assertEquals(440, observed.get("frequencyHz").getAsDouble(), .5);
    assertEquals(200, post("/api/tuner/stop", "{}").statusCode());
    assertFalse(input.open);
  }

  @Test
  void practiceUsesAuthenticatedMicrophoneAndSavesLocalSummary() throws Exception {
    assertEquals(409, get("/api/practice").statusCode());
    assertEquals(
        400,
        post(
                "/api/practice/create",
                "{\"kind\":\"NOTE\",\"keyId\":\"A_MINOR\",\"octave\":4.5,\"scaleType\":\"NATURAL_MINOR\",\"bpm\":80,\"loop\":false,\"progressiveStep\":0}")
            .statusCode());
    var created =
        post(
            "/api/practice/create",
            "{\"kind\":\"NOTE\",\"keyId\":\"A_MINOR\",\"octave\":4,\"scaleType\":\"NATURAL_MINOR\",\"bpm\":80,\"loop\":false,\"progressiveStep\":0}");
    assertEquals(200, created.statusCode());
    assertTrue(created.body().contains("A4"));
    assertEquals(200, get("/flute-fingering-cc0.jpg").statusCode());
    assertEquals(
        200,
        post("/api/tuner/start", "{\"deviceId\":\"test\",\"referenceHz\":440,\"consent\":true}")
            .statusCode());
    assertEquals(200, post("/api/practice/start", "{}").statusCode());
    for (int i = 0; i < 4; i++) {
      float[] samples = new float[4096];
      for (int j = 0; j < samples.length; j++)
        samples[j] = (float) (.5 * Math.sin(2 * Math.PI * 440 * j / 48000));
      input.frames.accept(new AudioFrame(samples, 48000, Instant.now(), i));
      TimeUnit.MILLISECONDS.sleep(50);
    }
    boolean completed = false;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      var result =
          JsonParser.parseString(get("/api/practice/observe").body())
              .getAsJsonObject()
              .getAsJsonObject("practice");
      if (result.getAsJsonObject("feedback").get("state").getAsString().equals("COMPLETE")) {
        completed = true;
        break;
      }
      TimeUnit.MILLISECONDS.sleep(25);
    }
    assertTrue(completed);
    var finished = post("/api/practice/finish", "{}");
    assertEquals(200, finished.statusCode());
    assertEquals(
        1,
        JsonParser.parseString(get("/api/practice/history").body())
            .getAsJsonObject()
            .getAsJsonArray("entries")
            .size());
    assertFalse(Files.readString(temporary.resolve("history.json")).contains("frequencyHz"));
    assertEquals(200, post("/api/tuner/stop", "{}").statusCode());
  }

  @Test
  void servesBundledUiAndLicenseWithSecurityHeaders() throws Exception {
    var page = get("/");
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("<app-root>"));
    assertFalse(page.body().contains("<script>"), "CSP forbids inline script execution");
    assertFalse(
        page.body().contains("data-beasties-media"), "Styles must load without inline script");
    assertTrue(
        page.headers()
            .firstValue("Content-Security-Policy")
            .orElseThrow()
            .contains("frame-ancestors 'none'"));
    assertFalse(page.body().contains(token));
    assertEquals(200, get("/license.txt").statusCode());
    assertEquals(404, get("/../LICENSE").statusCode());
  }

  @Test
  void releasesDeviceWhenUiStopsPolling() throws Exception {
    post("/api/tuner/start", "{\"deviceId\":\"test\",\"referenceHz\":440,\"consent\":true}");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (input.open && System.nanoTime() < deadline) {
      input.frames.accept(new AudioFrame(new float[4096], 48000, Instant.now(), 0));
      TimeUnit.MILLISECONDS.sleep(50);
    }
    assertFalse(input.open, "Capture must stop without a UI heartbeat");
  }

  @Test
  void importsScoreThroughAuthenticatedBridgeAndControlsTransport() throws Exception {
    byte[] score =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "two-parts.musicxml"));
    var unauthorized =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/score/open"))
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(score))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, unauthorized.statusCode());
    assertEquals(409, get("/api/score").statusCode());
    var opened =
        client.send(
            request("/api/score/open")
                .header("Content-Type", "application/xml")
                .header("X-Arpeon-Filename", "two-parts.musicxml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(score))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, opened.statusCode());
    var imported = JsonParser.parseString(opened.body()).getAsJsonObject();
    assertEquals("Two parts", imported.getAsJsonObject("score").get("title").getAsString());
    assertEquals(2, imported.getAsJsonObject("score").getAsJsonArray("measures").size());
    assertEquals(2, imported.getAsJsonObject("transport").getAsJsonArray("parts").size());
    assertEquals(90, imported.getAsJsonObject("transport").get("bpm").getAsDouble());
    assertEquals(200, post("/api/transport/seek", "{\"tick\":1920}").statusCode());
    var state = JsonParser.parseString(get("/api/transport").body()).getAsJsonObject();
    assertEquals(1, state.get("measureIndex").getAsInt());
    assertEquals(1920, state.get("tick").getAsLong());
    assertEquals(120, state.get("bpm").getAsDouble());
    assertEquals(200, post("/api/transport/bpm", "{\"bpm\":108}").statusCode());
    state = JsonParser.parseString(get("/api/transport").body()).getAsJsonObject();
    assertEquals(108, state.get("startingBpm").getAsDouble());
    assertEquals(144, state.get("bpm").getAsDouble());
    assertEquals(200, post("/api/transport/mute", "{\"id\":\"P1\",\"enabled\":true}").statusCode());
    assertEquals(400, post("/api/transport/seek", "{\"tick\":-1}").statusCode());
    assertEquals(
        400, post("/api/transport/solo", "{\"id\":\"other\",\"enabled\":true}").statusCode());
    assertEquals(200, post("/api/transport/stop", "{}").statusCode());
    assertFalse(input.open, "Score controls must not activate microphone capture");
  }

  @Test
  void importsPublicDtdMxlAndMuseScoreMsczThroughCompressedScoreRoute() throws Exception {
    Path fixtures = Path.of(System.getProperty("arpeon.fixtures"));
    for (String filename :
        List.of("badinerie-for-flute-by-js-bach.mxl", "badinerie-for-flute-by-js-bach.mscz")) {
      byte[] archive = Files.readAllBytes(fixtures.resolve(filename));
      var opened =
          client.send(
              request("/api/score/open-mxl")
                  .header("Content-Type", "application/octet-stream")
                  .header("X-Arpeon-Filename", filename)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(archive))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, opened.statusCode(), opened.body());
      var imported = JsonParser.parseString(opened.body()).getAsJsonObject();
      assertEquals("Badinerie", imported.getAsJsonObject("score").get("title").getAsString());
      assertTrue(imported.getAsJsonObject("score").getAsJsonArray("measures").size() >= 42);
    }
  }

  @Test
  void chordHeadsRoundTripThroughScoreApiAndLiveAnalysisRejectsTheirOverlap() throws Exception {
    String xml =
        Files.readString(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml"));
    String firstNote =
        "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><type>quarter</type></note>";
    String chordHead =
        "<note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration>"
            + "<type>quarter</type></note>";
    assertTrue(xml.contains(firstNote));
    xml = xml.replace(firstNote, firstNote + chordHead);
    var opened =
        client.send(
            request("/api/score/open")
                .header("Content-Type", "application/xml")
                .header("X-Arpeon-Filename", "chord.musicxml")
                .POST(HttpRequest.BodyPublishers.ofString(xml))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, opened.statusCode());

    var measure =
        JsonParser.parseString(get("/api/score/measure-events?partId=P1&measureIndex=0").body())
            .getAsJsonObject();
    var chord = measure.getAsJsonArray("events").get(0).getAsJsonObject();
    assertEquals("CHORD", chord.get("kind").getAsString());
    assertEquals(2, chord.getAsJsonArray("heads").size());
    assertEquals(
        "E4", chord.getAsJsonArray("heads").get(1).getAsJsonObject().get("pitch").getAsString());

    assertEquals(
        200,
        post(
                "/api/score/edit/set-pitch",
                "{\"partId\":\"P1\",\"measureIndex\":0,\"eventIndex\":0,"
                    + "\"headIndex\":1,\"step\":\"F\",\"alter\":1,\"octave\":4}")
            .statusCode());
    assertTrue(
        get("/api/score/export")
            .body()
            .contains("<step>F</step><alter>1</alter><octave>4</octave>"));
    String settings =
        "{\"partId\":\"P1\",\"deviceId\":\"test\",\"referenceHz\":440,"
            + "\"consent\":true,\"alignmentWindowMs\":450,\"timingToleranceMs\":120,"
            + "\"pitchToleranceCents\":50,\"rhythmTolerancePercent\":20,"
            + "\"latencyCompensationMs\":0}";
    assertEquals(400, post("/api/analysis/start", settings).statusCode());
    assertFalse(input.open, "Polyphonic analysis must fail before activating microphone capture");
  }

  @Test
  void independentVoicesAreVisibleEditableAndExportedWithTheirOffsets() throws Exception {
    String xml =
        "<score-partwise version=\"4.0\"><part-list><score-part"
            + " id=\"P1\"><part-name>Piano</part-name></score-part></part-list><part"
            + " id=\"P1\"><measure number=\"1\">"
            + "<attributes><divisions>4</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>"
            + "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice></note>"
            + "<backup><duration>4</duration></backup>"
            + "<note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><voice>2</voice></note>"
            + "<forward><duration>12</duration></forward></measure></part></score-partwise>";
    var opened =
        client.send(
            request("/api/score/open")
                .header("Content-Type", "application/xml")
                .header("X-Arpeon-Filename", "voices.musicxml")
                .POST(HttpRequest.BodyPublishers.ofString(xml))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, opened.statusCode());

    var measure =
        JsonParser.parseString(get("/api/score/measure-events?partId=P1&measureIndex=0").body())
            .getAsJsonObject();
    var events = measure.getAsJsonArray("events");
    assertEquals(2, events.size());
    assertEquals("1", events.get(0).getAsJsonObject().get("voiceId").getAsString());
    assertEquals("2", events.get(1).getAsJsonObject().get("voiceId").getAsString());
    assertEquals(0, events.get(1).getAsJsonObject().get("onsetNumerator").getAsInt());

    assertEquals(
        200,
        post(
                "/api/score/edit/set-pitch",
                "{\"partId\":\"P1\",\"measureIndex\":0,\"voiceId\":\"2\","
                    + "\"eventIndex\":0,\"step\":\"F\",\"alter\":1,\"octave\":4}")
            .statusCode());
    String exported = get("/api/score/export").body();
    assertTrue(exported.contains("<backup><duration>480</duration></backup>"));
    assertTrue(exported.contains("<voice>2</voice>"));
    assertTrue(exported.contains("<step>F</step><alter>1</alter><octave>4</octave>"));
    var reimported =
        new MusicXmlImporter()
            .read(
                new ByteArrayInputStream(
                    exported.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "voices.musicxml");
    assertEquals(2, reimported.score().parts().getFirst().measures().getFirst().voices().size());
    assertFalse(input.open);
  }

  @Test
  void rejectsUnsafeScoreWithoutReplacingOpenScore() throws Exception {
    var xxe =
        client.send(
            request("/api/score/open")
                .header("Content-Type", "application/xml")
                .header("X-Arpeon-Filename", "score.musicxml")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "<!DOCTYPE score-partwise SYSTEM"
                            + " 'https://example.invalid/x'><score-partwise/>"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, xxe.statusCode());
    assertEquals(409, get("/api/score").statusCode());
  }

  @Test
  void editsThroughAuthenticatedBridgePreviewUndoRedoAndExport() throws Exception {
    byte[] source =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "two-parts.musicxml"));
    var opened =
        client.send(
            request("/api/score/open")
                .header("Content-Type", "application/xml")
                .header("X-Arpeon-Filename", "two-parts.musicxml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(source))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, opened.statusCode());
    var first = JsonParser.parseString(opened.body()).getAsJsonObject();
    assertEquals(0, first.getAsJsonObject("score").get("revision").getAsInt());
    assertEquals(
        200,
        post("/api/score/edit/set-instrument", "{\"partId\":\"P1\",\"instrumentId\":\"flute\"}")
            .statusCode());
    assertEquals(
        "flute",
        JsonParser.parseString(get("/api/transport").body())
            .getAsJsonObject()
            .getAsJsonArray("parts")
            .get(0)
            .getAsJsonObject()
            .get("instrumentId")
            .getAsString());
    var preview =
        post("/api/score/edit/preview", "{\"kind\":\"octave\",\"partId\":\"P1\",\"semitones\":12}");
    assertEquals(200, preview.statusCode());
    var review =
        JsonParser.parseString(preview.body()).getAsJsonObject().getAsJsonObject("preview");
    assertEquals(5, review.get("changedNotes").getAsInt());
    assertTrue(review.get("xml").getAsString().contains("<octave>5</octave>"));
    assertTrue(get("/api/score/export").body().contains("<octave>4</octave>"));
    assertEquals(400, post("/api/score/edit/apply-preview", "{\"token\":\"wrong\"}").statusCode());
    String token = review.get("token").getAsString();
    var applied = post("/api/score/edit/apply-preview", "{\"token\":\"" + token + "\"}");
    assertEquals(200, applied.statusCode());
    assertEquals(
        2,
        JsonParser.parseString(applied.body())
            .getAsJsonObject()
            .getAsJsonObject("score")
            .get("revision")
            .getAsInt());
    var exported = get("/api/score/export");
    assertEquals(200, exported.statusCode());
    assertTrue(exported.body().contains("<octave>5</octave>"));
    assertEquals(200, post("/api/score/edit/undo", "{}").statusCode());
    assertTrue(get("/api/score/export").body().contains("<octave>4</octave>"));
    assertEquals(200, post("/api/score/edit/redo", "{}").statusCode());
    assertTrue(get("/api/score/export").body().contains("<octave>5</octave>"));
    assertEquals(400, post("/api/score/edit/set-pitch", "{\"partId\":\"P1\"}").statusCode());
    assertFalse(input.open);
  }

  @Test
  void keyPreviewTransposesEveryPartBeforeWritingOneScoreSignature() throws Exception {
    byte[] source =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "two-parts.musicxml"));
    assertEquals(
        200,
        client
            .send(
                request("/api/score/open")
                    .header("Content-Type", "application/xml")
                    .header("X-Arpeon-Filename", "two-parts.musicxml")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(source))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var preview =
        post(
            "/api/score/edit/preview",
            "{\"kind\":\"key\",\"partId\":\"P1\",\"sourceKey\":\"C_MAJOR\",\"targetKey\":\"Db_MAJOR\"}");
    assertEquals(200, preview.statusCode());
    var review =
        JsonParser.parseString(preview.body()).getAsJsonObject().getAsJsonObject("preview");
    assertEquals(7, review.get("changedNotes").getAsInt());
    String token = review.get("token").getAsString();
    assertEquals(
        200, post("/api/score/edit/apply-preview", "{\"token\":\"" + token + "\"}").statusCode());
    String exported = get("/api/score/export").body();
    assertEquals(2, exported.split("<fifths>-5</fifths>", -1).length - 1);
    assertTrue(exported.contains("<step>D</step><alter>-1</alter><octave>3</octave>"));
    assertEquals(200, post("/api/score/edit/undo", "{}").statusCode());
    assertFalse(get("/api/score/export").body().contains("<fifths>"));
  }

  @Test
  void instrumentalPreviewPreservesSoundingPitchAndExportsTransposeMetadata() throws Exception {
    byte[] source =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "two-parts.musicxml"));
    assertEquals(
        200,
        client
            .send(
                request("/api/score/open")
                    .header("Content-Type", "application/xml")
                    .header("X-Arpeon-Filename", "two-parts.musicxml")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(source))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        400,
        post(
                "/api/score/edit/set-instrument",
                "{\"partId\":\"P1\",\"instrumentId\":\"bb-clarinet\"}")
            .statusCode());
    var preview =
        post(
            "/api/score/edit/preview",
            "{\"kind\":\"instrument\",\"partId\":\"P1\",\"targetInstrument\":\"bb-clarinet\"}");
    assertEquals(200, preview.statusCode());
    String token =
        JsonParser.parseString(preview.body())
            .getAsJsonObject()
            .getAsJsonObject("preview")
            .get("token")
            .getAsString();
    assertEquals(
        200, post("/api/score/edit/apply-preview", "{\"token\":\"" + token + "\"}").statusCode());
    String xml = get("/api/score/export").body();
    assertTrue(xml.contains("<midi-program>72</midi-program>"));
    assertTrue(xml.contains("<chromatic>-2</chromatic>"));
    assertTrue(xml.contains("<step>D</step><alter>0</alter><octave>4</octave>"));
    var reopened =
        client.send(
            request("/api/score/open")
                .header("Content-Type", "application/xml")
                .header("X-Arpeon-Filename", "adapted.musicxml")
                .POST(HttpRequest.BodyPublishers.ofString(xml))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, reopened.statusCode());
    assertEquals(
        "bb-clarinet",
        JsonParser.parseString(reopened.body())
            .getAsJsonObject()
            .getAsJsonObject("transport")
            .getAsJsonArray("parts")
            .get(0)
            .getAsJsonObject()
            .get("instrumentId")
            .getAsString());
  }

  @Test
  void configuresLoopWithoutCaptureAndPreservesItAcrossEdits() throws Exception {
    openFirstNotes();
    String loop = "{\"startMeasureIndex\":1,\"endMeasureIndex\":1,\"enabled\":true}";
    var applied = post("/api/transport/loop", loop);
    assertEquals(200, applied.statusCode());
    var range = JsonParser.parseString(applied.body()).getAsJsonObject().getAsJsonObject("loop");
    assertEquals(1920, range.get("startTick").getAsLong());
    assertEquals(3840, range.get("endTick").getAsLong());
    assertEquals(
        400,
        post("/api/transport/loop", loop.replace("\"endMeasureIndex\":1", "\"endMeasureIndex\":0"))
            .statusCode());
    assertEquals(
        400,
        post(
                "/api/transport/loop",
                loop.replace("\"startMeasureIndex\":1", "\"startMeasureIndex\":1.5"))
            .statusCode());
    assertEquals(400, post("/api/transport/seek", "{\"tick\":0}").statusCode());
    assertEquals(
        200,
        post("/api/score/edit/set-instrument", "{\"partId\":\"P1\",\"instrumentId\":\"flute\"}")
            .statusCode());
    assertEquals(
        range,
        JsonParser.parseString(get("/api/transport").body())
            .getAsJsonObject()
            .getAsJsonObject("loop"));
    assertEquals(200, post("/api/transport/loop", loop.replace("true", "false")).statusCode());
    assertEquals(200, post("/api/transport/seek", "{\"tick\":0}").statusCode());
    assertFalse(input.open);
  }

  @Test
  void staleCaptureCleanupCannotStopAnotherOwnerAndPlaybackIsBlockedDuringCapture()
      throws Exception {
    openFirstNotes();
    String capture = "{\"deviceId\":\"test\",\"referenceHz\":440,\"consent\":true}";
    assertEquals(200, postOwned("/api/tuner/start", capture, "first").statusCode());
    assertEquals(409, post("/api/transport/play", "{}").statusCode());
    assertTrue(input.open);
    assertEquals(409, postOwned("/api/tuner/start", capture, "second").statusCode());
    assertEquals(200, postOwned("/api/tuner/stop", "{}", "first").statusCode());
    assertEquals(200, postOwned("/api/tuner/start", capture, "second").statusCode());
    assertEquals(200, postOwned("/api/tuner/stop", "{}", "first").statusCode());
    assertTrue(input.open);
    assertEquals(200, postOwned("/api/tuner/stop", "{}", "second").statusCode());
    assertFalse(input.open);
  }

  @Test
  void abandonedAnalysisReleasesItsLeaseAndAllowsAnotherCapture() throws Exception {
    openFirstNotes();
    String analysis =
        "{\"partId\":\"P1\",\"deviceId\":\"test\",\"referenceHz\":440,\"consent\":true,\"alignmentWindowMs\":450,\"timingToleranceMs\":120,\"pitchToleranceCents\":50,\"rhythmTolerancePercent\":20,\"latencyCompensationMs\":0}";
    assertEquals(200, postOwned("/api/analysis/start", analysis, "closed-window").statusCode());
    server.expireAudioLeases(System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
    assertFalse(input.open);
    assertEquals(
        "STOPPED",
        JsonParser.parseString(get("/api/analysis").body())
            .getAsJsonObject()
            .get("status")
            .getAsString());
    String capture = "{\"deviceId\":\"test\",\"referenceHz\":440,\"consent\":true}";
    assertEquals(200, postOwned("/api/tuner/start", capture, "new-window").statusCode());
    assertTrue(input.open);
    assertEquals(200, postOwned("/api/tuner/stop", "{}", "new-window").statusCode());
  }

  @Test
  void concurrentCaptureStartsGrantOnlyOneOwner() throws Exception {
    String capture = "{\"deviceId\":\"test\",\"referenceHz\":440,\"consent\":true}";
    var attempts =
        List.of("first", "second").stream()
            .map(
                owner ->
                    client.sendAsync(
                        request("/api/tuner/start")
                            .header("Content-Type", "application/json")
                            .header("X-Arpeon-Capture-Owner", owner)
                            .POST(HttpRequest.BodyPublishers.ofString(capture))
                            .build(),
                        HttpResponse.BodyHandlers.ofString()))
            .toList();
    var responses = List.of(attempts.get(0).get(), attempts.get(1).get());
    assertEquals(
        List.of(200, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList());
    String owner = responses.get(0).statusCode() == 200 ? "first" : "second";
    assertTrue(input.open);
    assertEquals(200, postOwned("/api/tuner/stop", "{}", owner).statusCode());
    assertFalse(input.open);
  }

  @Test
  void practiceAttemptBlocksReferencePlaybackAndAnalysisOwnsItsCapture() throws Exception {
    openFirstNotes();
    String exercise =
        "{\"kind\":\"NOTE\",\"keyId\":\"C_MAJOR\",\"octave\":4,\"scaleType\":\"MAJOR\",\"bpm\":80,\"loop\":false,\"progressiveStep\":0}";
    assertEquals(200, post("/api/practice/create", exercise).statusCode());
    assertEquals(200, post("/api/practice/start", "{}").statusCode());
    assertEquals(409, post("/api/practice/playback/play", "{}").statusCode());
    assertEquals(409, post("/api/transport/play", "{}").statusCode());
    assertEquals(200, post("/api/practice/finish", "{}").statusCode());
    String analysis =
        "{\"partId\":\"P1\",\"deviceId\":\"test\",\"referenceHz\":440,\"consent\":true,\"alignmentWindowMs\":450,\"timingToleranceMs\":120,\"pitchToleranceCents\":50,\"rhythmTolerancePercent\":20,\"latencyCompensationMs\":0}";
    assertEquals(200, postOwned("/api/analysis/start", analysis, "analysis-window").statusCode());
    assertEquals(409, post("/api/transport/play", "{}").statusCode());
    assertEquals(409, post("/api/practice/start", "{}").statusCode());
    assertEquals(200, postOwned("/api/tuner/stop", "{}", "old-tuner").statusCode());
    assertEquals(200, postOwned("/api/analysis/stop", "{}", "old-analysis").statusCode());
    assertTrue(input.open);
    assertEquals(
        "COUNT_IN",
        JsonParser.parseString(get("/api/analysis").body())
            .getAsJsonObject()
            .get("status")
            .getAsString());
    assertEquals(200, postOwned("/api/analysis/stop", "{}", "analysis-window").statusCode());
    assertFalse(input.open);
  }

  @Test
  void libraryOperationsPreserveTheActiveSessionAndValidateRequests() throws Exception {
    openFirstNotes();
    var saved = JsonParser.parseString(post("/api/projects", "{}").body()).getAsJsonObject();
    String id = saved.get("id").getAsString();
    String scoreBefore = get("/api/score").body();
    String renamePath = "/api/projects/" + id + "/rename";
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(origin + renamePath))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Study\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(400, post(renamePath, "{\"name\":\" \"}").statusCode());
    assertEquals(400, post(renamePath, "{\"name\":\"Study\",\"unknown\":true}").statusCode());
    assertEquals(400, post(renamePath, "{\"name\":5}").statusCode());
    assertEquals(200, post(renamePath, "{\"name\":\"Estudo diário\"}").statusCode());
    assertEquals(scoreBefore, get("/api/score").body());
    assertEquals(
        200, post("/api/projects", "{\"id\":\"" + id + "\",\"autosave\":true}").statusCode());
    assertEquals(
        "Estudo diário",
        JsonParser.parseString(get("/api/projects").body())
            .getAsJsonArray()
            .get(0)
            .getAsJsonObject()
            .get("title")
            .getAsString());
    var copied = post("/api/projects/" + id + "/duplicate", "{\"name\":\"Estudo lento\"}");
    assertEquals(200, copied.statusCode());
    String copyId = JsonParser.parseString(copied.body()).getAsJsonObject().get("id").getAsString();
    assertFalse(id.equals(copyId));
    assertEquals(
        id,
        JsonParser.parseString(get("/api/session").body())
            .getAsJsonObject()
            .get("projectId")
            .getAsString());
    assertEquals(scoreBefore, get("/api/score").body());
    assertEquals(2, JsonParser.parseString(get("/api/projects").body()).getAsJsonArray().size());
    assertEquals(200, post("/api/projects/" + copyId + "/open", "{}").statusCode());
    assertFalse(input.open);
  }

  @Test
  void studyPreparationUsesReportIdentityAndCannotStartAudioOrInterruptAnAttempt()
      throws Exception {
    openFirstNotes();
    var started =
        post(
            "/api/analysis/start",
            """
            {"partId":"P1","deviceId":"test","referenceHz":440,"consent":true,
            "alignmentWindowMs":450,"timingToleranceMs":120,"pitchToleranceCents":50,
            "rhythmTolerancePercent":20,"latencyCompensationMs":0,"mode":"ANALYSIS"}
            """);
    assertEquals(200, started.statusCode());
    var view = JsonParser.parseString(started.body()).getAsJsonObject();
    var study = view.getAsJsonObject("study");
    assertEquals(view.get("expectedCount").getAsInt(), study.getAsJsonArray("notes").size());
    var preparation = new com.google.gson.JsonObject();
    preparation.add("sha256", view.get("sha256"));
    preparation.add("revision", view.get("revision"));
    preparation.add("partId", view.get("partId"));
    preparation.add("soundingSemitones", view.get("soundingSemitones"));
    preparation.add("scoreXmlSha256", study.get("scoreXmlSha256"));
    preparation.addProperty("expectedIndex", 0);
    preparation.addProperty("bpm", 60);
    preparation.addProperty("repeat", true);
    assertEquals(409, post("/api/score/study", preparation.toString()).statusCode());
    assertTrue(input.open);
    assertEquals(200, post("/api/analysis/stop", "{}").statusCode());
    assertFalse(input.open);
    var anonymous =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/score/study"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(preparation.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, anonymous.statusCode());
    var response = post("/api/score/study", preparation.toString());
    assertEquals(200, response.statusCode());
    var selected = JsonParser.parseString(response.body()).getAsJsonObject();
    assertEquals("P1", selected.getAsJsonObject("transport").get("activePartId").getAsString());
    assertNotEquals("PLAYING", selected.getAsJsonObject("transport").get("status").getAsString());
    assertEquals(0, selected.getAsJsonObject("location").get("eventIndex").getAsInt());
    assertFalse(input.open);
    var before = get("/api/transport").body();
    preparation.addProperty("expectedIndex", 1.5);
    assertEquals(400, post("/api/score/study", preparation.toString()).statusCode());
    preparation.addProperty("expectedIndex", 0);
    preparation.addProperty("revision", 9);
    assertEquals(409, post("/api/score/study", preparation.toString()).statusCode());
    assertEquals(before, get("/api/transport").body());
  }

  private void openFirstNotes() throws Exception {
    var content =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml"));
    assertEquals(
        200,
        client
            .send(
                request("/api/score/open")
                    .header("Content-Type", "application/xml")
                    .header("X-Arpeon-Filename", "first-notes.musicxml")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  private HttpResponse<String> postOwned(String path, String body, String owner) throws Exception {
    return client.send(
        request(path)
            .header("Content-Type", "application/json")
            .header("X-Arpeon-Capture-Owner", owner)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(origin + path))
        .timeout(Duration.ofSeconds(4))
        .header("X-Arpeon-Session", token);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static final class TestInput implements AudioInputPort {
    volatile boolean open;
    Consumer<AudioFrame> frames;

    @Override
    public List<AudioInputDevice> devices() {
      return List.of(new AudioInputDevice("test", "Test input", List.of(48000)));
    }

    @Override
    public void start(String id, Consumer<AudioFrame> onFrame, Consumer<String> failure) {
      open = true;
      frames = onFrame;
    }

    @Override
    public void stop() {
      open = false;
    }

    @Override
    public long overruns() {
      return 0;
    }

    @Override
    public void close() {
      stop();
    }
  }
}
