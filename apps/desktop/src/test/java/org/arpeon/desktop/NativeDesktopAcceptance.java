package org.arpeon.desktop;

import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.arpeon.audio.JavaSoundAudioInput;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.arpeon.tuner.TunerService;

/** Explicit acceptance runner, not part of the application JAR. Uses the real UI and parsers. */
public final class NativeDesktopAcceptance {
  private NativeDesktopAcceptance() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 4)
      throw new IllegalArgumentException(
          "Expected package, isolated profile, output, original MusicXML fixture");
    Path packageRoot = Path.of(args[0]).toAbsolutePath();
    Path output = Files.createDirectories(Path.of(args[2]).toAbsolutePath());
    System.setProperty("arpeon.installDir", packageRoot.toString());
    System.setProperty("arpeon.dataDir", Path.of(args[1]).toAbsolutePath().toString());
    System.setProperty(
        "arpeon.audiveris.home", packageRoot.resolve("tools/audiveris-5.11.0").toString());
    UserDirectories.ensureData();
    Files.createDirectories(UserDirectories.data().resolve("logs"));
    try (var server =
        new DesktopServer(new TunerService(new JavaSoundAudioInput(), new YinPitchDetector()))) {
      server.start();
      try (var window = new DesktopWindow(server.launchUri(), () -> {})) {
        awaitDocument(window, "Nenhuma partitura aberta", 30);
        window.capture(output.resolve("desktop-empty.png"));
        String xml = new Gson().toJson(Files.readString(Path.of(args[3])));
        window.execute(
            "const input=document.querySelector('input[type=file]');const files=new"
                + " DataTransfer();files.items.add(new File(["
                + xml
                + "], 'first-notes.musicxml',"
                + " {type:'application/xml'}));input.files=files.files;input.dispatchEvent(new"
                + " Event('change',{bubbles:true}));");
        String document = awaitDocument(window, "osmdSvgPage1", 40);
        Files.writeString(output.resolve("score-dom.html"), document);
        window.capture(output.resolve("desktop-score.png"));
        window.execute(
            "const svg=document.querySelector('#osmdSvgPage1').cloneNode(true);const image=new"
                + " Image();image.onload=()=>{const"
                + " canvas=document.createElement('canvas');canvas.width=image.width*3;canvas.height=image.height*3;const"
                + " ctx=canvas.getContext('2d');"
                + "ctx.fillStyle='white';ctx.fillRect(0,0,canvas.width,canvas.height);"
                + "ctx.drawImage(image,0,0,canvas.width,canvas.height);"
                + "document.body.setAttribute('data-printable-score',canvas.toDataURL('image/png'));};image.src='data:image/svg+xml;base64,'+btoa(unescape(encodeURIComponent(new"
                + " XMLSerializer().serializeToString(svg))));");
        String printable = awaitDocument(window, "data-printable-score=", 15);
        var match =
            java.util.regex.Pattern.compile(
                    "data-printable-score=\"data:image/png;base64,([^\"]+)\"")
                .matcher(printable);
        if (!match.find()) throw new AssertionError("Printable score was not rendered");
        Files.write(
            output.resolve("original-score.png"),
            java.util.Base64.getDecoder().decode(match.group(1)));
        URI launch = server.launchUri();
        String token = launch.getFragment().substring("session=".length());
        URI origin = URI.create(launch.getScheme() + "://" + launch.getAuthority());
        try (var http = HttpClient.newHttpClient()) {
          long savedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
          String projects;
          do {
            projects = get(http, origin.resolve("/api/projects"), token);
            if (!projects.equals("[]")) break;
            Thread.sleep(100);
          } while (System.nanoTime() < savedDeadline);
          if (projects.equals("[]")) throw new AssertionError("Imported project did not autosave");
          int before = new Gson().fromJson(projects, com.google.gson.JsonArray.class).size();
          awaitSaveEnabled(window);
          window.execute(
              "document.dispatchEvent(new"
                  + " KeyboardEvent('keydown',{key:'S',ctrlKey:true,shiftKey:true,bubbles:true}));");
          long copyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
          int after;
          do {
            after =
                new Gson()
                    .fromJson(
                        get(http, origin.resolve("/api/projects"), token),
                        com.google.gson.JsonArray.class)
                    .size();
            if (after > before) break;
            Thread.sleep(100);
          } while (System.nanoTime() < copyDeadline);
          if (after != before + 1)
            throw new AssertionError("Save As did not preserve the original project");
          awaitSaveEnabled(window);
          window.execute(
              "const zoom=document.querySelector('.zoom-control"
                  + " select');zoom.value='125';zoom.dispatchEvent(new"
                  + " Event('change',{bubbles:true}));");
          long preferencesDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          String preferences;
          do {
            preferences = get(http, origin.resolve("/api/preferences"), token);
            if (preferences.contains("\"zoom\":125")) break;
            Thread.sleep(100);
          } while (System.nanoTime() < preferencesDeadline);
          if (!preferences.contains("\"zoom\":125"))
            throw new AssertionError("Zoom preference did not persist");
          window.resize(800, 600);
          Thread.sleep(500);
          window.capture(output.resolve("desktop-narrow.png"));
          window.resize(1280, 840);
          for (var area :
              new String[][] {
                {"Importar", "app-import-panel", "import"},
                {"Aprendizado", "app-practice-panel", "practice"},
                {"Ferramentas", "app-tuner-panel", "tuner"},
                {"Análise", "app-analysis-panel", "analysis"}
              }) {
            window.execute(
                "Array.from(document.querySelectorAll('.activity-bar"
                    + " button')).find(button=>button.title==="
                    + new Gson().toJson(area[0])
                    + "||button.textContent.trim()==="
                    + new Gson().toJson(area[0])
                    + ").click();");
            awaitDocument(window, area[1], 15);
            Thread.sleep(400);
            window.capture(output.resolve("desktop-" + area[2] + ".png"));
          }
          window.execute(
              "Array.from(document.querySelectorAll('.activity-bar"
                  + " button')).find(button=>button.textContent.trim()==='Importar').click();");
          awaitDocument(window, "app-import-panel", 15);
          window.execute(
              "Array.from(document.querySelectorAll('.source-tabs"
                  + " button')).find(button=>button.textContent.trim()==='Arquivo').click();");
          String pdf =
              java.util.Base64.getEncoder()
                  .encodeToString(
                      Files.readAllBytes(
                          Path.of(args[3]).getParent().resolve("../omr/first-notes.pdf")));
          window.execute(
              "const"
                  + " omrInput=document.querySelector('app-import-panel input[type=file]');const"
                  + " omrFiles=new DataTransfer();omrFiles.items.add(new"
                  + " File([Uint8Array.from(atob('"
                  + pdf
                  + "'),c=>c.charCodeAt(0))], 'first-notes.pdf',"
                  + " {type:'application/pdf'}));omrInput.files=omrFiles.files;omrInput.dispatchEvent(new"
                  + " Event('change',{bubbles:true}));");
          String review = awaitDocument(window, "class=\"omr-pdf-page\"", 180);
          if (!review.contains("download=\"first-notes.pdf\"")
              || !review.contains("C4")
              || !review.contains("A4"))
            throw new AssertionError("OMR review did not expose original and recognized pitches");
          window.capture(output.resolve("desktop-omr-review.png"));
          window.execute(
              "const acknowledgment=document.querySelector('.acknowledge"
                  + " input');acknowledgment.checked=true;acknowledgment.dispatchEvent(new"
                  + " Event('change',{bubbles:true}));");
          Thread.sleep(100);
          window.execute(
              "Array.from(document.querySelectorAll('app-import-panel"
                  + " button')).find(button=>button.textContent.trim()==='Abrir rascunho no"
                  + " editor').click();");
          awaitDocument(window, "osmdSvgPage1", 30);
          String recognizedXml = get(http, origin.resolve("/api/score/export"), token);
          Files.writeString(output.resolve("ui-recognized.musicxml"), recognizedXml);
          var recognized =
              new org.arpeon.musicxml.MusicXmlImporter()
                  .read(
                      recognizedXml.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      "recognized.musicxml");
          var pitches =
              recognized.score().parts().stream()
                  .flatMap(part -> part.measures().stream())
                  .flatMap(measure -> measure.voices().stream())
                  .flatMap(voice -> voice.events().stream())
                  .flatMap(event -> event.event().noteHeads().stream())
                  .map(note -> note.writtenPitch().label())
                  .toList();
          if (!pitches.equals(java.util.List.of("C4", "D#4", "A4")))
            throw new AssertionError("UI OMR import differs from original: " + pitches);
          long omrSaveDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
          int recognizedProjects;
          do {
            recognizedProjects =
                new Gson()
                    .fromJson(
                        get(http, origin.resolve("/api/projects"), token),
                        com.google.gson.JsonArray.class)
                    .size();
            if (recognizedProjects > after) break;
            Thread.sleep(100);
          } while (System.nanoTime() < omrSaveDeadline);
          if (recognizedProjects != after + 1)
            throw new AssertionError("Recognized score did not autosave as a separate project");
          awaitSaveEnabled(window);
          window.capture(output.resolve("desktop-omr-score.png"));
          Files.writeString(
              output.resolve("native-desktop-result.json"),
              "{\"nativeWindow\":true,\"angularLoaded\":true,\"musicXmlImported\":true,\"scoreSvgRendered\":true,\"autosave\":true,\"saveAsPreservedOriginal\":true,\"zoomPersisted\":true,\"narrowWindowCaptured\":true,\"toolViewsCaptured\":true,\"pdfRecognizedThroughUi\":true,\"omrReviewOriginalRendered\":true,\"omrAcceptedThroughUi\":true,\"recognizedScoreAutosaved\":true}");
        }
      }
    }
    System.exit(0);
  }

  private static String get(HttpClient http, URI uri, String token) throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("X-Arpeon-Session", token)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new AssertionError("Local request failed: " + response.statusCode());
    return response.body();
  }

  private static void awaitSaveEnabled(DesktopWindow window) throws Exception {
    String marker = "data-save-ready-" + java.util.UUID.randomUUID();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    do {
      window.execute(
          "document.body.setAttribute('"
              + marker
              + "',String(Array.from(document.querySelectorAll('.menubar"
              + " button')).some(button=>button.textContent.trim().startsWith('Salvar"
              + " como')&&!button.disabled)));");
      if (window.document().get(5, TimeUnit.SECONDS).contains(marker + "=\"true\"")) return;
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Native UI did not enable Save As after autosave");
  }

  private static String awaitDocument(DesktopWindow window, String text, int seconds)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    String document = "";
    do {
      try {
        document = window.document().get(5, TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException initializing) {
        continue;
      }
      if (document.contains(text)) return document;
      Thread.sleep(150);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Native UI did not reach expected state: " + text);
  }
}
