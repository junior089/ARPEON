package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.arpeon.audio.JavaSoundAudioInput;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.arpeon.musicxml.MusicXmlImporter;
import org.arpeon.omr.OmrResult;
import org.arpeon.tuner.TunerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OmrCancellationTest {
  @TempDir Path directory;

  private static byte[] image() throws Exception {
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(120, 120, BufferedImage.TYPE_BYTE_GRAY), "png", bytes);
    return bytes.toByteArray();
  }

  private static OmrResult result(String filename) throws Exception {
    var source =
        Files.readAllBytes(Path.of(System.getProperty("arpeon.fixtures"), "first-notes.musicxml"));
    return new OmrResult(
        new MusicXmlImporter().read(source, "recognized.xml"),
        "a".repeat(64),
        filename,
        1,
        List.of("Revisar."),
        List.of(1),
        List.of());
  }

  @Test
  void cancellationBeforeUploadPreventsStartingTheNativeEngine() throws Exception {
    var calls = new AtomicInteger();
    var result = result("Canção – flauta.png");
    var workspace =
        new OmrWorkspace(
            input -> {
              calls.incrementAndGet();
              return result;
            });
    String job = UUID.randomUUID().toString();
    assertTrue(workspace.cancel(job));
    assertThrows(
        java.io.IOException.class, () -> workspace.preview(image(), "Canção – flauta.png", job));
    assertEquals(0, calls.get());
    assertFalse(workspace.status().recognizing());
    assertNotNull(workspace.preview(image(), "Canção – flauta.png", UUID.randomUUID().toString()));
  }

  @Test
  void authenticatedCancelCanInterruptRecognitionAndNeverPublishesItsLateResult() throws Exception {
    var started = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var result = result("Canção – flauta.png");
    var workspace =
        new OmrWorkspace(
            input -> {
              if (calls.incrementAndGet() == 1) {
                started.countDown();
                try {
                  new CountDownLatch(1).await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                  /* Simulate a backend that returns a result after interruption. */
                  Thread.currentThread().interrupt();
                }
              }
              return result;
            });
    try (var server =
            new DesktopServer(
                new TunerService(new JavaSoundAudioInput(), new YinPitchDetector()),
                new PracticeHistory(directory.resolve("history.json")),
                new ImportWorkspace(),
                workspace);
        var client = HttpClient.newHttpClient()) {
      server.start();
      URI launch = server.launchUri();
      String origin = "http://127.0.0.1:" + launch.getPort();
      String secret = launch.getFragment().substring("session=".length());
      String job = UUID.randomUUID().toString();
      var request =
          HttpRequest.newBuilder(URI.create(origin + "/api/omr/preview"))
              .header("X-Arpeon-Session", secret)
              .header("X-Arpeon-Job", job)
              .header(
                  "X-Arpeon-Filename",
                  URLEncoder.encode("Canção – flauta.png", java.nio.charset.StandardCharsets.UTF_8))
              .header("Content-Type", "application/octet-stream")
              .POST(HttpRequest.BodyPublishers.ofByteArray(image()))
              .build();
      var recognition = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
      assertTrue(started.await(5, TimeUnit.SECONDS));
      var status =
          client.send(
              HttpRequest.newBuilder(URI.create(origin + "/api/omr/status"))
                  .header("X-Arpeon-Session", secret)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertTrue(
          JsonParser.parseString(status.body())
              .getAsJsonObject()
              .get("recognizing")
              .getAsBoolean());
      var unauthorized =
          client.send(
              HttpRequest.newBuilder(URI.create(origin + "/api/omr/cancel"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{\"jobId\":\"" + job + "\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(401, unauthorized.statusCode());
      assertFalse(workspace.cancel(UUID.randomUUID().toString()));
      var cancelled =
          client.send(
              HttpRequest.newBuilder(URI.create(origin + "/api/omr/cancel"))
                  .header("X-Arpeon-Session", secret)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{\"jobId\":\"" + job + "\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, cancelled.statusCode());
      assertEquals(422, recognition.get(7, TimeUnit.SECONDS).statusCode());
      assertFalse(workspace.status().recognizing());
      assertEquals("cancelled", workspace.status().recognitionPhase());
      assertThrows(IllegalStateException.class, () -> workspace.accept("unpublished"));
      String nextJob = UUID.randomUUID().toString();
      var next = workspace.preview(image(), "Canção – flauta.png", nextJob);
      assertTrue(workspace.cancel(nextJob));
      assertThrows(IllegalStateException.class, () -> workspace.accept(next.token()));
    }
  }
}
