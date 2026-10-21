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
    if (args.length != 4 && !(args.length == 5 && args[4].equals("mutopia-live")))
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
        await(window, "Identificar edição", 20);
        window.execute(
            "Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='Identificar"
                + " edição').click();");
        await(window, "Cancelar identificação", 10);
        window.execute(
            "const fields=document.querySelectorAll('app-edition-panel input');for(const [i,value]"
                + " of ['Edição nativa','Página de rosto, chapa"
                + " 123'].entries()){fields[i].value=value;fields[i].dispatchEvent(new"
                + " Event('input',{bubbles:true}));}");
        window.execute(
            "document.querySelector('app-edition-panel form').dispatchEvent(new"
                + " Event('submit',{bubbles:true,cancelable:true}));");
        await(window, "Identificação registrada", 20);
        var evidence =
            JsonParser.parseString(
                    get(http, sessions.launchUri(), "/api/projects/" + projectId + "/editions"))
                .getAsJsonObject();
        if (evidence.getAsJsonArray("history").size() != 1
            || !evidence
                .getAsJsonArray("history")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("edition")
                .get("title")
                .getAsString()
                .equals("Edição nativa"))
          throw new AssertionError("Native edition declaration was not persisted");
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
        if (args.length == 5) acceptMutopia(window, http, sessions, output);
        Files.writeString(
            output.resolve("result.json"),
            "{\"success\":true,\"checks\":[\"native session rotation\",\"MusicXML import and"
                + " save\",\"empty second library\",\"restored first library\",\"provenance and"
                + " original integrity\",\"native edition declaration persisted\"]}");
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

  private static void acceptMutopia(
      DesktopWindow window, HttpClient http, ProfileSessions sessions, Path output)
      throws Exception {
    window.execute("document.querySelector('.import-button').click();");
    await(window, "Abrir e importar", 10);
    window.execute(
        "Array.from(document.querySelectorAll('.source-tabs"
            + " button')).find(b=>b.textContent.trim()==='Mutopia').click();");
    await(window, "Analisar Allemande BWV 1013", 20);
    window.capture(output.resolve("mutopia-catalogue-native.png"));
    window.execute("document.querySelector('app-mutopia-catalogue button.primary').click();");
    await(window, "Prévia da importação", 40);
    if (JsonParser.parseString(get(http, sessions.launchUri(), "/api/projects"))
            .getAsJsonArray()
            .size()
        != 1) throw new AssertionError("Preview replaced the active project");
    window.execute(
        "document.querySelector('.import-preview input[type=checkbox]').click();const"
            + " started=performance.now();function acceptReady(){const"
            + " button=document.querySelector('.import-preview"
            + " button.primary');if(button&&!button.disabled){button.click();}else"
            + " if(performance.now()-started<5000){requestAnimationFrame(acceptReady);}}requestAnimationFrame(acceptReady);");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    String project = null;
    do {
      for (var entry :
          JsonParser.parseString(get(http, sessions.launchUri(), "/api/projects"))
              .getAsJsonArray()) {
        var item = entry.getAsJsonObject();
        if (item.get("filename").getAsString().equals("bwv1013.mid"))
          project = item.get("id").getAsString();
      }
      if (project == null) Thread.sleep(100);
    } while (project == null && System.nanoTime() < deadline);
    if (project == null) throw new AssertionError("Native Mutopia project was not saved");
    var original =
        http.send(
            request(sessions.launchUri(), "/api/projects/" + project + "/original").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    if (original.statusCode() != 200
        || !ProjectProvenance.hash(original.body())
            .equals(MutopiaCatalogue.entries().getFirst().sha256()))
      throw new AssertionError("Native Mutopia original failed verification");
    window.execute("document.querySelector('button[title=\"Biblioteca\"]').click();");
    await(window, "bwv1013.mid", 20);
    window.execute(
        "Array.from(document.querySelectorAll('[aria-label]')).find(b=>(b.getAttribute('aria-label')||'').startsWith('Gerenciar"
            + " projeto Allemande')).click();");
    await(window, "6011 bytes", 20);
    window.capture(output.resolve("mutopia-original-native.png"));
    Files.writeString(
        output.resolve("mutopia-live-result.json"),
        "{\"success\":true,\"checks\":[\"live approved Mutopia download\",\"native review and"
            + " accept\",\"autosave\",\"original MIDI hash verified\"]}");
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
