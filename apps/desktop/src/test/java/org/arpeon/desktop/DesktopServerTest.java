package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
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
import java.util.function.Consumer;
import org.arpeon.audio.AudioFrame;
import org.arpeon.audio.AudioInputDevice;
import org.arpeon.audio.AudioInputPort;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.arpeon.tuner.TunerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopServerTest {
  @TempDir Path temporary;
  private DesktopServer server;
  private HttpClient client;
  private String origin;
  private String token;
  private TestInput input;

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
            new PracticeHistory(temporary.resolve("history.json")));
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
        1, JsonParser.parseString(get("/api/practice/history").body()).getAsJsonArray().size());
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
  void rejectsOversizedOrUnsafeScoreWithoutReplacingOpenScore() throws Exception {
    var oversized =
        client.send(
            request("/api/score/open")
                .header("Content-Type", "application/xml")
                .header("X-Arpeon-Filename", "large.musicxml")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1_048_577)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(413, oversized.statusCode());
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
