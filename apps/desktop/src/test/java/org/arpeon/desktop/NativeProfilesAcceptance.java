package org.arpeon.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.arpeon.audio.JavaSoundAudioInput;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.arpeon.tuner.TunerService;

/** Opt-in acceptance of profile handover inside the shipped Chromium window. */
public final class NativeProfilesAcceptance {
  private NativeProfilesAcceptance() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 4)
      throw new IllegalArgumentException(
          "Expected package, isolated data, output and MusicXML fixture");
    System.setProperty("arpeon.installDir", Path.of(args[0]).toAbsolutePath().toString());
    System.setProperty("arpeon.dataDir", Path.of(args[1]).toAbsolutePath().toString());
    UserDirectories.ensureData();
    Files.createDirectories(UserDirectories.data().resolve("logs"));
    Path output = Files.createDirectories(Path.of(args[2]).toAbsolutePath());
    var json = new Gson();
    try (var sessions =
            new ProfileSessions(
                UserDirectories.data(),
                8,
                () -> new TunerService(new JavaSoundAudioInput(), new YinPitchDetector()));
        var http = HttpClient.newHttpClient()) {
      sessions.start();
      URI initial = sessions.launchUri();
      String first = sessions.account().snapshot().activeProfileId();
      try (var window = new DesktopWindow(initial, () -> {}, sessions::launchUri)) {
        await(window, "Quem vai estudar?", 30);
        window.capture(output.resolve("profiles-selector-native.png"));
        var created =
            send(
                http,
                initial,
                "/api/profiles",
                "{\"name\":\"Piano"
                    + " teste\",\"avatar\":\"blue\",\"instrumentId\":\"piano\",\"secondaryInstrumentIds\":[]}");
        if (created.statusCode() != 200)
          throw new AssertionError("Profile creation failed: " + created.statusCode());
        window.execute(
            "document.querySelector('[aria-label=\"Estudar com Meu estudo\"]').click();");
        await(window, "Nenhuma partitura aberta", 30);
        if (sessions.launchUri().equals(initial))
          throw new AssertionError("Native navigation did not rotate the session");
        String xml = json.toJson(Files.readString(Path.of(args[3])));
        window.execute(
            "const input=document.querySelector('input[aria-label=\"Abrir arquivo de"
                + " partitura\"]');const data=new DataTransfer();data.items.add(new File(["
                + xml
                + "], 'first-notes.musicxml',{type:'application/xml'}));input.files=data.files;input.dispatchEvent(new"
                + " Event('change',{bubbles:true}));");
        await(window, "osmdSvgPage1", 30);
        await(window, "Projeto salvo", 20);
        window.execute(
            "document.querySelector('[aria-label=\"Trocar perfil: Meu estudo\"]').click();");
        await(window, "Quem vai estudar?", 20);
        window.execute(
            "document.querySelector('[aria-label=\"Estudar com Piano teste\"]').click();");
        await(window, "Nenhuma partitura aberta", 30);
        if (!get(http, sessions.launchUri(), "/api/projects").equals("[]"))
          throw new AssertionError("The new profile inherited the first library");
        window.capture(output.resolve("profiles-empty-piano-native.png"));
        window.execute(
            "document.querySelector('[aria-label=\"Trocar perfil: Piano teste\"]').click();");
        await(window, "Quem vai estudar?", 20);
        window.execute(
            "document.querySelector('[aria-label=\"Estudar com Meu estudo\"]').click();");
        await(window, "Nenhuma partitura aberta", 30);
        window.execute("document.querySelector('button[title=\"Biblioteca\"]').click();");
        await(window, "first-notes.musicxml", 20);
        window.execute(
            "document.querySelector('[aria-label=\"Gerenciar projeto First notes\"]').click();");
        await(window, "Preservado e verificado", 20);
        if (!window.document().get(5, TimeUnit.SECONDS).contains("Direitos não avaliados"))
          throw new AssertionError("Source rights state was not shown");
        var entries =
            JsonParser.parseString(get(http, sessions.launchUri(), "/api/projects"))
                .getAsJsonArray();
        String projectId = entries.get(0).getAsJsonObject().get("id").getAsString();
        var original =
            http.send(
                request(sessions.launchUri(), "/api/projects/" + projectId + "/original")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (original.statusCode() != 200
            || !java.util.Arrays.equals(Files.readAllBytes(Path.of(args[3])), original.body()))
          throw new AssertionError("Original bytes were not restored");
        if (JsonParser.parseString(get(http, sessions.launchUri(), "/api/projects"))
                .getAsJsonArray()
                .size()
            != 1) throw new AssertionError("The first library was not restored");
        if (!sessions.account().snapshot().activeProfileId().equals(first))
          throw new AssertionError("Wrong active profile after return");
        window.capture(output.resolve("profiles-library-native.png"));
        Files.writeString(
            output.resolve("result.json"),
            "{\"success\":true,\"checks\":[\"native session rotation\",\"MusicXML import and"
                + " save\",\"empty second library\",\"restored first library\",\"provenance and"
                + " original integrity\"]}");
      }
    }
    System.out.println("Native profile acceptance passed.");
    System.exit(0);
  }

  private static String await(DesktopWindow window, String needle, int seconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    String html = "";
    do {
      try {
        html = window.document().get(2, TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException starting) {
        continue;
      }
      if (html.contains(needle)) return html;
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Native page did not contain: " + needle);
  }

  private static HttpRequest.Builder request(URI launch, String path) {
    return HttpRequest.newBuilder(launch.resolve(path))
        .timeout(java.time.Duration.ofSeconds(10))
        .header("X-Arpeon-Session", launch.getFragment().substring("session=".length()));
  }

  private static HttpResponse<String> send(HttpClient client, URI launch, String path, String body)
      throws Exception {
    return client.send(
        request(launch, path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String get(HttpClient client, URI launch, String path) throws Exception {
    var response =
        client.send(request(launch, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new AssertionError("Native API status " + response.statusCode());
    return response.body();
  }
}
