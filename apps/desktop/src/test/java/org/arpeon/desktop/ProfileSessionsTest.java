package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.arpeon.audio.AudioFrame;
import org.arpeon.audio.AudioInputDevice;
import org.arpeon.audio.AudioInputPort;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.arpeon.tuner.TunerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileSessionsTest {
  @TempDir Path root;
  private URI launch;
  private MockInput input;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @AfterEach
  void closeClient() {
    client.close();
  }

  private ProfileSessions host() throws Exception {
    var host =
        new ProfileSessions(
            root,
            8,
            () -> {
              input = new MockInput();
              return new TunerService(input, new YinPitchDetector());
            });
    host.start();
    launch = host.launchUri();
    return host;
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(launch.resolve(path))
        .timeout(Duration.ofSeconds(10))
        .header("X-Arpeon-Session", launch.getFragment().substring("session=".length()));
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(request(path).GET().build());
  }

  private HttpResponse<String> write(String path, String body, String method) throws Exception {
    return send(
        request(path)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return write(path, body, "POST");
  }

  private JsonObject account() throws Exception {
    return JsonParser.parseString(get("/api/account").body()).getAsJsonObject();
  }

  private void select(String id) throws Exception {
    var result = post("/api/profiles/select", "{\"profileId\":\"" + id + "\"}");
    assertEquals(200, result.statusCode(), result.body());
    launch =
        URI.create(
            JsonParser.parseString(result.body()).getAsJsonObject().get("launchUri").getAsString());
  }

  private void openScore() throws Exception {
    byte[] xml =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml"));
    assertEquals(
        200,
        send(request("/api/score/open")
                .header("Content-Type", "application/xml")
                .header("X-Arpeon-Filename", "first-notes.musicxml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(xml))
                .build())
            .statusCode());
  }

  @Test
  void scopesProjectsPreferencesHistoriesAndTokensAcrossProfileSwitchAndRestart() throws Exception {
    String first;
    String piano;
    String project;
    try (var host = host()) {
      first = account().get("activeProfileId").getAsString();
      assertTrue(account().get("selectionRequired").getAsBoolean());
      assertEquals(409, get("/api/projects").statusCode());
      assertEquals(
          200,
          post(
                  "/api/profiles",
                  "{\"name\":\"Piano\",\"avatar\":\"blue\",\"instrumentId\":\"piano\",\"secondaryInstrumentIds\":[\"violin\"]}")
              .statusCode());
      piano = account().getAsJsonArray("profiles").get(1).getAsJsonObject().get("id").getAsString();
      URI previous = launch;
      select(first);
      assertNotEquals(previous.getPort(), launch.getPort());
      assertNotEquals(previous.getFragment(), launch.getFragment());
      assertEquals(
          401,
          send(HttpRequest.newBuilder(launch.resolve("/api/projects"))
                  .header("X-Arpeon-Session", previous.getFragment().substring("session=".length()))
                  .GET()
                  .build())
              .statusCode());
      openScore();
      var saved = post("/api/projects", "{}");
      assertEquals(200, saved.statusCode(), saved.body());
      project = JsonParser.parseString(saved.body()).getAsJsonObject().get("id").getAsString();
      assertEquals(
          200,
          write("/api/preferences", PreferencesStoreTest.settings().toString(), "PUT")
              .statusCode());
      var summary =
          new PracticeWorkspace.Summary(
              "attempt",
              "NOTE",
              "C_MAJOR",
              "2026-10-19T12:00:00Z",
              "2026-10-19T12:01:00Z",
              true,
              1,
              1,
              0,
              null,
              0,
              80);
      new PracticeHistory(host.account().profileDirectory(first).resolve("practice-history.json"))
          .add(summary);
      assertEquals(
          1,
          JsonParser.parseString(get("/api/practice/history").body())
              .getAsJsonObject()
              .get("totalCount")
              .getAsInt());
      select(piano);
      assertEquals("[]", get("/api/projects").body());
      assertEquals("null", get("/api/preferences").body());
      assertEquals(
          0,
          JsonParser.parseString(get("/api/practice/history").body())
              .getAsJsonObject()
              .get("totalCount")
              .getAsInt());
      assertEquals(404, post("/api/projects/" + project + "/open", "{}").statusCode());
      assertEquals(
          404, post("/api/projects/" + project + "/rename", "{\"name\":\"Other\"}").statusCode());
      assertEquals(404, post("/api/projects", "{\"id\":\"" + project + "\"}").statusCode());
      assertEquals(404, send(request("/api/projects/" + project).DELETE().build()).statusCode());
      var preferences = PreferencesStoreTest.settings();
      preferences.addProperty("theme", "dark");
      preferences.addProperty("zoom", 150);
      assertEquals(200, write("/api/preferences", preferences.toString(), "PUT").statusCode());
      select(first);
      assertTrue(get("/api/projects").body().contains(project));
      assertEquals(
          125,
          JsonParser.parseString(get("/api/preferences").body())
              .getAsJsonObject()
              .get("zoom")
              .getAsInt());
      assertEquals(
          1,
          JsonParser.parseString(get("/api/practice/history").body())
              .getAsJsonObject()
              .get("totalCount")
              .getAsInt());
      host.account().preferences("Minha conta", true);
    }
    try (var restarted = host()) {
      assertTrue(restarted.account().snapshot().openLastProfile());
      assertEquals(first, account().get("activeProfileId").getAsString());
      assertFalse(account().get("selectionRequired").getAsBoolean());
      assertTrue(get("/api/projects").body().contains(project));
      select(piano);
      assertEquals(
          150,
          JsonParser.parseString(get("/api/preferences").body())
              .getAsJsonObject()
              .get("zoom")
              .getAsInt());
    }
  }

  @Test
  void rejectsUntrustedRequestsAndInvalidMetadataWithoutChangingTheAccount() throws Exception {
    try (var host = host()) {
      var before = host.account().snapshot();
      assertEquals(
          401,
          send(HttpRequest.newBuilder(launch.resolve("/api/account")).GET().build()).statusCode());
      assertEquals(
          403,
          send(request("/api/account").header("Origin", "https://example.invalid").GET().build())
              .statusCode());
      assertEquals(400, post("/api/profiles", "null").statusCode());
      assertEquals(400, post("/api/profiles", "{broken").statusCode());
      assertEquals(
          400, post("/api/profiles/select", "{\"profileId\":\"../../other\"}").statusCode());
      assertEquals(
          400,
          post(
                  "/api/profiles",
                  "{\"name\":\"Piano\",\"avatar\":\"blue\",\"instrumentId\":\"piano\",\"secondaryInstrumentIds\":[null]}")
              .statusCode());
      assertEquals(before, host.account().snapshot());
    }
  }

  @Test
  void refusesSwitchWithUnsavedMusicOrActiveCaptureAndPreservesTheSession() throws Exception {
    try (var host = host()) {
      String id = account().get("activeProfileId").getAsString();
      select(id);
      URI active = launch;
      openScore();
      assertEquals(
          409, post("/api/profiles/select", "{\"profileId\":\"" + id + "\"}").statusCode());
      assertEquals(active, host.launchUri());
      assertEquals(200, post("/api/projects", "{}").statusCode());
      assertEquals(
          200,
          post("/api/tuner/start", "{\"deviceId\":\"test\",\"referenceHz\":440,\"consent\":true}")
              .statusCode());
      assertTrue(input.open);
      assertEquals(
          409, post("/api/profiles/select", "{\"profileId\":\"" + id + "\"}").statusCode());
      assertEquals(active, host.launchUri());
      assertEquals(200, post("/api/tuner/stop", "{}").statusCode());
      select(id);
      assertFalse(input.open);
    }
  }

  @Test
  void refusesHandoverWhileAnotherRequestIsInFlight() throws Exception {
    try (var host = host()) {
      String id = account().get("activeProfileId").getAsString();
      select(id);
      input.entered = new CountDownLatch(1);
      input.release = new CountDownLatch(1);
      var pending =
          client.sendAsync(
              request("/api/devices").GET().build(), HttpResponse.BodyHandlers.ofString());
      try {
        assertTrue(input.entered.await(5, TimeUnit.SECONDS));
        assertEquals(
            409, post("/api/profiles/select", "{\"profileId\":\"" + id + "\"}").statusCode());
        assertEquals(launch, host.launchUri());
      } finally {
        input.release.countDown();
      }
      assertEquals(200, pending.get(5, TimeUnit.SECONDS).statusCode());
      select(id);
    }
  }

  static final class MockInput implements AudioInputPort {
    volatile boolean open;
    volatile CountDownLatch entered;
    volatile CountDownLatch release;

    @Override
    public List<AudioInputDevice> devices() {
      if (entered != null) {
        entered.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS))
            throw new IllegalStateException("Timed out waiting for test request");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }
      return List.of(new AudioInputDevice("test", "Synthetic input", List.of(48000)));
    }

    @Override
    public void start(String id, Consumer<AudioFrame> frames, Consumer<String> errors) {
      open = true;
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
