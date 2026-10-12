package org.arpeon.desktop;

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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.arpeon.audio.JavaSoundAudioInput;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.arpeon.musicxml.MusicXmlImporter;
import org.arpeon.tuner.TunerService;

/** Opt-in native acceptance; never substitutes a runner or recognition result. */
public final class BundledOmrAcceptance {
  private BundledOmrAcceptance() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 4)
      throw new IllegalArgumentException(
          "Expected Audiveris home, isolated profile, fixtures and results");
    System.setProperty("arpeon.audiveris.home", Path.of(args[0]).toAbsolutePath().toString());
    System.setProperty("arpeon.dataDir", Path.of(args[1]).toAbsolutePath().toString());
    Path temp = Files.createDirectories(Path.of(args[1]).resolve("tmp").toAbsolutePath());
    System.setProperty("arpeon.omr.tempDir", temp.toString());
    Path output = Files.createDirectories(Path.of(args[3]));
    UserDirectories.ensureData();
    var log = ApplicationLog.open();
    try (var server =
            new DesktopServer(new TunerService(new JavaSoundAudioInput(), new YinPitchDetector()));
        var http = HttpClient.newHttpClient()) {
      server.start();
      URI launch = server.launchUri();
      URI origin = URI.create(launch.getScheme() + "://" + launch.getAuthority());
      String token = launch.getFragment().substring("session=".length());
      for (String filename : List.of("first-notes.pdf", "first-notes.png")) {
        byte[] bytes = Files.readAllBytes(Path.of(args[2]).resolve(filename));
        var preview =
            http.send(
                upload(origin, token, filename, bytes, UUID.randomUUID().toString()),
                HttpResponse.BodyHandlers.ofString());
        require(preview.statusCode() == 200, "Recognition failed: " + preview.body());
        JsonObject recognized = JsonParser.parseString(preview.body()).getAsJsonObject();
        var accepted =
            http.send(
                json(
                    origin.resolve("/api/omr/accept"),
                    token,
                    "{\"token\":\""
                        + recognized.get("token").getAsString()
                        + "\",\"acknowledge\":true}"),
                HttpResponse.BodyHandlers.ofString());
        require(
            accepted.statusCode() == 200,
            "OMR could not enter the score workspace: " + accepted.body());
        String xml =
            JsonParser.parseString(accepted.body()).getAsJsonObject().get("xml").getAsString();
        var imported =
            new MusicXmlImporter()
                .read(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), "recognized.musicxml");
        var notes =
            imported.score().parts().stream()
                .flatMap(part -> part.measures().stream())
                .flatMap(measure -> measure.voices().stream())
                .flatMap(voice -> voice.events().stream())
                .flatMap(event -> event.event().noteHeads().stream())
                .map(note -> note.writtenPitch().label())
                .toList();
        require(
            notes.equals(List.of("C4", "D#4", "A4")),
            "Recognition differs from the original fixture: " + notes);
        var measures = imported.score().parts().getFirst().measures();
        require(measures.size() == 2, "Recognition changed the number of measures");
        var durations =
            measures.stream()
                .flatMap(measure -> measure.voices().stream())
                .flatMap(voice -> voice.events().stream())
                .map(event -> event.event().duration())
                .toList();
        require(
            durations.equals(
                List.of(
                    new org.arpeon.domain.time.Duration(1, 4),
                    new org.arpeon.domain.time.Duration(1, 4),
                    new org.arpeon.domain.time.Duration(1, 2),
                    new org.arpeon.domain.time.Duration(1, 1))),
            "Recognition changed the source rhythm: " + durations);
        Files.writeString(output.resolve(filename + ".musicxml"), xml);
        Files.writeString(output.resolve(filename + ".preview.json"), preview.body());
        System.out.println(filename + " recognized into the score workspace: " + notes);
      }
      var invalid =
          http.send(
              upload(
                  origin, token, "corrupt.pdf", new byte[] {1, 2, 3}, UUID.randomUUID().toString()),
              HttpResponse.BodyHandlers.ofString());
      require(invalid.statusCode() == 422 || invalid.statusCode() == 400, "Corrupt PDF accepted");
      String job = UUID.randomUUID().toString();
      var running =
          http.sendAsync(
              upload(
                  origin,
                  token,
                  "cancel.pdf",
                  Files.readAllBytes(Path.of(args[2]).resolve("first-notes.pdf")),
                  job),
              HttpResponse.BodyHandlers.ofString());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      ProcessHandle nativeJob = null;
      do {
        nativeJob =
            ProcessHandle.current()
                .descendants()
                .filter(child -> child.info().command().orElse("").endsWith("Audiveris.exe"))
                .findFirst()
                .orElse(null);
        if (nativeJob != null) break;
        Thread.sleep(100);
      } while (System.nanoTime() < deadline && !running.isDone());
      require(nativeJob != null, "Cancellation did not exercise a real native process");
      var cancelled =
          http.send(
              json(origin.resolve("/api/omr/cancel"), token, "{\"jobId\":\"" + job + "\"}"),
              HttpResponse.BodyHandlers.ofString());
      require(cancelled.statusCode() == 200, "Cancellation request failed");
      require(
          running.get(15, TimeUnit.SECONDS).statusCode() == 422,
          "Cancelled recognition was accepted");
      require(!nativeJob.isAlive(), "Cancelled native process survived");
      try (var remaining = Files.list(temp)) {
        require(
            remaining.noneMatch(path -> path.getFileName().toString().startsWith("arpeon-omr-")),
            "OMR temporary job leaked");
      }
      Files.writeString(
          output.resolve("result.json"),
          "{\"pdf\":true,\"png\":true,\"notesCompared\":true,\"rhythmCompared\":true,\"scoreWorkspace\":true,\"corruptPdfRejected\":true,\"nativeCancellation\":true,\"temporaryJobsRemoved\":true}");
    } finally {
      log.close();
    }
  }

  private static HttpRequest upload(
      URI origin, String token, String name, byte[] bytes, String job) {
    return HttpRequest.newBuilder(origin.resolve("/api/omr/preview"))
        .timeout(Duration.ofMinutes(4))
        .header("X-Arpeon-Session", token)
        .header("Content-Type", "application/octet-stream")
        .header("X-Arpeon-Filename", name)
        .header("X-Arpeon-Job", job)
        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
        .build();
  }

  private static HttpRequest json(URI uri, String token, String json) {
    return HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(20))
        .header("X-Arpeon-Session", token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
