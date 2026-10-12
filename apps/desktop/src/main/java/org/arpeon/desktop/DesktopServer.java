package org.arpeon.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.arpeon.analysis.AnalysisConfig;
import org.arpeon.importer.LocalImporters;
import org.arpeon.musicxml.MusicXmlImporter;
import org.arpeon.omr.AudiverisOmrEngine;
import org.arpeon.omr.OmrInputValidator;
import org.arpeon.tuner.TunerService;
import org.arpeon.tuner.TuningReference;

/** Same-origin loopback bridge. Microphone samples and local filesystem paths stay in Java. */
public final class DesktopServer implements AutoCloseable {
  private final TunerService tuner;
  private final ScoreWorkspace workspace = new ScoreWorkspace();
  private final AnalysisWorkspace analysis;
  private final AnalysisHistory analysisHistory = AnalysisHistory.defaultHistory();
  private final ImportWorkspace imports;
  private final OmrWorkspace omr;
  private final PracticeWorkspace practice = new PracticeWorkspace();
  private final PracticeHistory practiceHistory;
  private final ProjectStore projects;
  private final PreferencesStore preferences;
  private final AudioPreferencesStore audioPreferences;
  private final Object audioControl = new Object();
  private final java.util.concurrent.Semaphore pdfPreviewSlots =
      new java.util.concurrent.Semaphore(1);
  private String tunerOwner;
  private String analysisOwner;
  private final HttpServer server;
  private final ExecutorService requests =
      new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32));
  private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
  private final ThreadPoolExecutor autosaveWriter =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(2),
          task -> {
            var thread = new Thread(task, "arpeon-project-autosave");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private final AtomicBoolean closed = new AtomicBoolean();
  private final String token;
  private final String origin;
  private volatile long lastPollNanos;
  private volatile long lastTransportPollNanos;
  private volatile long lastPracticePollNanos;
  private volatile String currentProjectId;
  private volatile int savedProjectRevision = -1;
  private volatile WindowsUpdater updater;
  private volatile String analysisProjectId;
  private volatile long lastPersistedAnalysisStart = Long.MIN_VALUE;
  private volatile Runnable shutdownCallback = () -> {};
  private final Gson json =
      new GsonBuilder()
          .setStrictness(Strictness.STRICT)
          .serializeNulls()
          .registerTypeAdapter(
              Instant.class,
              new TypeAdapter<Instant>() {
                @Override
                public void write(JsonWriter out, Instant value) throws IOException {
                  out.value(value.toString());
                }

                @Override
                public Instant read(JsonReader in) throws IOException {
                  return Instant.parse(in.nextString());
                }
              }.nullSafe())
          .create();

  public DesktopServer(TunerService tuner) throws IOException {
    this(tuner, PracticeHistory.defaultHistory());
  }

  DesktopServer(TunerService tuner, PracticeHistory practiceHistory) throws IOException {
    this(tuner, practiceHistory, new ImportWorkspace());
  }

  DesktopServer(TunerService tuner, PracticeHistory practiceHistory, ImportWorkspace imports)
      throws IOException {
    this(tuner, practiceHistory, imports, new OmrWorkspace(new AudiverisOmrEngine()));
  }

  DesktopServer(
      TunerService tuner,
      PracticeHistory practiceHistory,
      ImportWorkspace imports,
      OmrWorkspace omr)
      throws IOException {
    this(tuner, practiceHistory, imports, omr, ProjectStore.defaultStore());
  }

  DesktopServer(
      TunerService tuner,
      PracticeHistory practiceHistory,
      ImportWorkspace imports,
      OmrWorkspace omr,
      ProjectStore projects)
      throws IOException {
    this(tuner, practiceHistory, imports, omr, projects, PreferencesStore.defaultStore());
  }

  DesktopServer(
      TunerService tuner,
      PracticeHistory practiceHistory,
      ImportWorkspace imports,
      OmrWorkspace omr,
      ProjectStore projects,
      PreferencesStore preferences)
      throws IOException {
    this(
        tuner,
        practiceHistory,
        imports,
        omr,
        projects,
        preferences,
        AudioPreferencesStore.defaultStore());
  }

  DesktopServer(
      TunerService tuner,
      PracticeHistory practiceHistory,
      ImportWorkspace imports,
      OmrWorkspace omr,
      ProjectStore projects,
      PreferencesStore preferences,
      AudioPreferencesStore audioPreferences)
      throws IOException {
    this.tuner = tuner;
    this.analysis = new AnalysisWorkspace(workspace, tuner);
    this.practiceHistory = practiceHistory;
    this.imports = imports;
    this.omr = omr;
    this.projects = projects;
    this.preferences = preferences;
    this.audioPreferences = audioPreferences;
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    server.createContext("/", this::handle);
    server.setExecutor(requests);
  }

  public URI launchUri() {
    return URI.create(origin + "/#session=" + token);
  }

  void onShutdown(Runnable callback) {
    shutdownCallback = callback;
  }

  void attachUpdater(WindowsUpdater value) {
    updater = value;
  }

  boolean canUpdate() {
    synchronized (audioControl) {
      if (omr.status().recognizing()
          || analysis.active()
          || practice.activeAttempt()
          || captureActive()) return false;
      synchronized (workspace) {
        if (!workspace.isOpen()) return true;
        return currentProjectId != null && workspace.revision() == savedProjectRevision;
      }
    }
  }

  public void start() {
    server.start();
    watchdog.scheduleWithFixedDelay(
        () -> {
          try {
            expireAudioLeases(System.nanoTime());
          } catch (RuntimeException failure) {
            System.getLogger(DesktopServer.class.getName())
                .log(System.Logger.Level.ERROR, "Audio lifecycle watchdog failed");
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
  }

  void expireAudioLeases(long now) {
    synchronized (audioControl) {
      synchronized (tuner) {
        if (lastPollNanos != 0 && now - lastPollNanos > TimeUnit.SECONDS.toNanos(5)) {
          if (analysis.active()) analysis.stop();
          else tuner.stop();
          tunerOwner = null;
          analysisOwner = null;
          lastPollNanos = 0;
        }
        tuner.checkCaptureHealth();
        if (analysis.active() && !captureActive()) analysis.observe();
        if (lastTransportPollNanos != 0
            && now - lastTransportPollNanos > TimeUnit.SECONDS.toNanos(5)) {
          workspace.stopIfPlaying();
          lastTransportPollNanos = 0;
        }
        if (lastPracticePollNanos != 0
            && now - lastPracticePollNanos > TimeUnit.SECONDS.toNanos(5)) {
          practice.stopIfPlaying();
          lastPracticePollNanos = 0;
        }
      }
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      var headers = exchange.getResponseHeaders();
      headers.set("Cache-Control", "no-store");
      headers.set("X-Content-Type-Options", "nosniff");
      headers.set("Referrer-Policy", "no-referrer");
      headers.set(
          "Content-Security-Policy",
          "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src"
              + " 'self'; img-src 'self' data: blob:; font-src 'self'; object-src"
              + " 'none'; base-uri 'self'; frame-ancestors 'none'");
      headers.set("Permissions-Policy", "microphone=(), camera=(), geolocation=()");
      String host = exchange.getRequestHeaders().getFirst("Host");
      String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
      String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
      if (!("127.0.0.1:" + server.getAddress().getPort()).equals(host)
          || (requestOrigin != null && !origin.equals(requestOrigin))
          || "cross-site".equals(site)) {
        sendJson(exchange, 403, Map.of("error", "Request origin denied"));
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.startsWith("/api/")) {
        String supplied = exchange.getRequestHeaders().getFirst("X-Arpeon-Session");
        if (supplied == null
            || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII))) {
          sendJson(
              exchange, 401, Map.of("error", "Launch ARPEON again to open an authorized session"));
          return;
        }
        api(exchange, path);
      } else staticFile(exchange, path);
    } catch (IOException failure) {
      // Preserve the cause of OMR disconnects so a failed browser fetch can be diagnosed.
      System.getLogger(DesktopServer.class.getName())
          .log(
              exchange.getRequestURI().getPath().startsWith("/api/omr/")
                  ? System.Logger.Level.WARNING
                  : System.Logger.Level.DEBUG,
              "Local UI connection closed",
              failure);
    }
  }

  private void api(HttpExchange exchange, String path) throws IOException {
    if (path.startsWith("/api/transport")
        || path.startsWith("/api/tuner")
        || path.startsWith("/api/practice")
        || path.startsWith("/api/analysis")) {
      synchronized (audioControl) {
        apiRequest(exchange, path);
      }
    } else apiRequest(exchange, path);
  }

  private void pdfPage(HttpExchange exchange) throws IOException {
    if (!"application/octet-stream".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
      sendJson(exchange, 415, Map.of("error", "Expected application/octet-stream"));
      return;
    }
    String encoded = exchange.getRequestHeaders().getFirst("X-Arpeon-Filename");
    String page = exchange.getRequestHeaders().getFirst("X-Arpeon-Page");
    if (encoded == null || encoded.length() > 1500 || page == null || !page.matches("[0-9]{1,2}"))
      throw new IllegalArgumentException("Invalid PDF page request");
    if (!pdfPreviewSlots.tryAcquire()) {
      sendJson(exchange, 409, Map.of("error", "Aguarde a preparação da página anterior."));
      return;
    }
    try {
      byte[] content = exchange.getRequestBody().readNBytes(OmrInputValidator.MAX_PDF_BYTES + 1);
      if (content.length > OmrInputValidator.MAX_PDF_BYTES) {
        sendJson(exchange, 413, Map.of("error", "PDF exceeds 64 MiB"));
        return;
      }
      try {
        byte[] png =
            org.arpeon.omr.OmrPdfPreview.render(
                new org.arpeon.omr.OmrInput(
                    content, URLDecoder.decode(encoded, StandardCharsets.UTF_8)),
                Integer.parseInt(page));
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, png.length);
        try (var output = exchange.getResponseBody()) {
          output.write(png);
        }
      } catch (IOException failure) {
        sendJson(exchange, 422, Map.of("error", failure.getMessage()));
      }
    } finally {
      pdfPreviewSlots.release();
    }
  }

  private void apiRequest(HttpExchange exchange, String path) throws IOException {
    String method = exchange.getRequestMethod();
    try {
      if (path.equals("/api/updates") && method.equals("GET")) {
        sendJson(
            exchange,
            200,
            updater == null
                ? new WindowsUpdater.Status(
                    false,
                    "stable",
                    AppInstallation.version(),
                    "Atualizações disponíveis após a publicação oficial.",
                    false,
                    "idle",
                    "")
                : updater.status());
      } else if (path.equals("/api/updates/preferences") && method.equals("PUT")) {
        JsonObject request = smallJson(exchange);
        if (!request.keySet().equals(Set.of("automaticChecking"))
            || !request.get("automaticChecking").isJsonPrimitive()
            || !request.get("automaticChecking").getAsJsonPrimitive().isBoolean()) {
          sendJson(exchange, 400, Map.of("error", "Preferência de atualização inválida."));
        } else if (updater == null || !updater.status().enabled()) {
          sendJson(exchange, 409, Map.of("error", "Atualizações indisponíveis neste pacote."));
        } else {
          updater.automaticChecking(request.get("automaticChecking").getAsBoolean());
          sendJson(exchange, 200, updater.status());
        }
      } else if (path.equals("/api/updates/check") && method.equals("POST")) {
        emptyJson(exchange);
        if (updater == null || !updater.status().enabled()) {
          sendJson(
              exchange,
              409,
              Map.of(
                  "error",
                  updater == null
                      ? "Atualizações disponíveis após a publicação oficial."
                      : updater.status().message()));
        } else {
          updater.check();
          sendJson(exchange, 200, updater.status());
        }
      } else if (path.equals("/api/preferences") && Set.of("GET", "PUT").contains(method)) {
        try {
          sendJson(
              exchange,
              200,
              method.equals("GET") ? preferences.load() : preferences.save(smallJson(exchange)));
        } catch (IOException failure) {
          System.getLogger(DesktopServer.class.getName())
              .log(System.Logger.Level.WARNING, "Local preferences operation failed", failure);
          sendJson(
              exchange,
              500,
              Map.of("error", "Não foi possível guardar as preferências neste computador."));
        }
      } else if (path.equals("/api/audio/preferences") && Set.of("GET", "PUT").contains(method)) {
        try {
          sendJson(
              exchange,
              200,
              method.equals("GET")
                  ? audioPreferences.load()
                  : audioPreferences.save(smallJson(exchange)));
        } catch (IOException failure) {
          sendJson(
              exchange,
              500,
              Map.of(
                  "error",
                  method.equals("GET")
                      ? "Não foi possível ler os ajustes de áudio. Escolha a entrada e guarde os"
                          + " ajustes novamente."
                      : "Não foi possível guardar os ajustes de áudio. Verifique o acesso à pasta"
                          + " do usuário e tente novamente."));
        }
      } else if (path.equals("/api/score/study") && method.equals("POST")) {
        if (analysis.active())
          throw new IllegalStateException(
              "Conclua ou cancele a tentativa antes de preparar um trecho.");
        sendJson(exchange, 200, workspace.prepareStudy(smallJson(exchange)));
      } else if (path.equals("/api/projects") && method.equals("GET")) {
        sendJson(exchange, 200, projects.list());
      } else if (path.equals("/api/projects") && method.equals("POST")) {
        JsonObject request = smallJson(exchange);
        if (request == null
            || !Set.of("id", "autosave").containsAll(request.keySet())
            || (request.has("id")
                && (!request.get("id").isJsonPrimitive()
                    || !request.get("id").getAsJsonPrimitive().isString()))
            || (request.has("autosave")
                && (!request.get("autosave").isJsonPrimitive()
                    || !request.get("autosave").getAsJsonPrimitive().isBoolean())))
          throw new IllegalArgumentException("Invalid project save request");
        String id = request.has("id") ? practiceString(request, "id") : null;
        boolean autosave = request.has("autosave") && practiceBoolean(request, "autosave");
        ProjectStore.Data data;
        int revision;
        synchronized (workspace) {
          data = workspace.projectData();
          revision = workspace.revision();
        }
        try {
          ProjectStore.Saved saved = saveProject(id, data, autosave);
          currentProjectId = saved.id();
          savedProjectRevision = revision;
          sendJson(exchange, 200, saved);
        } catch (RejectedExecutionException saturated) {
          sendJson(
              exchange,
              503,
              Map.of(
                  "error",
                  "O salvamento local está ocupado. Aguarde um instante e tente novamente."));
        } catch (IOException failure) {
          System.getLogger(DesktopServer.class.getName())
              .log(System.Logger.Level.ERROR, "Local project save failed", failure);
          sendJson(
              exchange,
              500,
              Map.of("error", "Não foi possível salvar o projeto neste computador."));
        }
      } else if (path.matches("/api/projects/[0-9a-f-]{36}/(rename|duplicate)")
          && method.equals("POST")) {
        JsonObject request = smallJson(exchange);
        if (request == null || !request.keySet().equals(Set.of("name")))
          throw new IllegalArgumentException("Informe apenas o nome do projeto.");
        if (!request.get("name").isJsonPrimitive()
            || !request.get("name").getAsJsonPrimitive().isString())
          throw new IllegalArgumentException("Informe o nome do projeto como texto.");
        String name = request.get("name").getAsString();
        String operation = path.substring(path.lastIndexOf('/') + 1);
        String id = path.substring("/api/projects/".length(), path.lastIndexOf('/'));
        try {
          var saved =
              operation.equals("rename") ? projects.rename(id, name) : projects.duplicate(id, name);
          sendJson(exchange, 200, saved);
        } catch (IOException failure) {
          System.getLogger(DesktopServer.class.getName())
              .log(System.Logger.Level.ERROR, "Local project library operation failed", failure);
          sendJson(
              exchange,
              500,
              Map.of(
                  "error",
                  "Não foi possível atualizar a biblioteca. O projeto original foi preservado."
                      + " Tente novamente."));
        }
      } else if (path.matches("/api/projects/[0-9a-f-]{36}") && method.equals("DELETE")) {
        String id = path.substring("/api/projects/".length());
        if (id.equals(analysisProjectId)) {
          synchronized (tuner) {
            analysis.stop();
            analysisProjectId = null;
          }
        }
        projects.delete(id);
        analysisHistory.deleteProject(id);
        if (id.equals(currentProjectId)) currentProjectId = null;
        sendJson(exchange, 200, Map.of("deleted", id));
      } else if (path.matches("/api/projects/[0-9a-f-]{36}/open") && method.equals("POST")) {
        emptyJson(exchange);
        String id = path.substring("/api/projects/".length(), path.length() - "/open".length());
        ProjectStore.Project project = projects.load(id);
        ScoreWorkspace.View view = workspace.openProject(project.data());
        currentProjectId = id;
        savedProjectRevision = view.score().revision();
        lastTransportPollNanos = 0;
        sendJson(exchange, 200, Map.of("id", id, "view", view));
      } else if (path.equals("/api/session") && method.equals("GET")) {
        sendJson(
            exchange,
            200,
            Map.of(
                "scoreOpen",
                workspace.isOpen(),
                "projectId",
                currentProjectId == null ? "" : currentProjectId));
      } else if (path.equals("/api/shutdown") && method.equals("POST")) {
        emptyJson(exchange);
        sendJson(exchange, 202, Map.of("shuttingDown", true));
        watchdog.schedule(
            () -> {
              try {
                close();
              } finally {
                shutdownCallback.run();
              }
            },
            500,
            TimeUnit.MILLISECONDS);
      } else if (path.equals("/api/analysis/history") && method.equals("GET")) {
        sendJson(exchange, 200, analysisHistory.summaries());
      } else if (path.matches("/api/analysis/history/[0-9a-f-]{36}") && method.equals("GET")) {
        sendJson(
            exchange, 200, analysisHistory.get(path.substring("/api/analysis/history/".length())));
      } else if (path.matches("/api/analysis/history/[0-9a-f-]{36}") && method.equals("DELETE")) {
        String id = path.substring("/api/analysis/history/".length());
        analysisHistory.delete(id);
        sendJson(exchange, 200, Map.of("deleted", id));
      } else if (path.equals("/api/import/providers") && method.equals("GET")) {
        sendJson(exchange, 200, SourceRegistry.all());
      } else if (path.equals("/api/omr/status") && method.equals("GET")) {
        sendJson(exchange, 200, omr.status());
      } else if (path.equals("/api/omr/cancel") && method.equals("POST")) {
        JsonObject request = smallJson(exchange);
        if (request == null || !request.keySet().equals(Set.of("jobId")))
          throw new IllegalArgumentException("Expected recognition jobId");
        String jobId = practiceString(request, "jobId");
        if (!jobId.matches("[a-fA-F0-9-]{36}"))
          throw new IllegalArgumentException("Invalid recognition jobId");
        sendJson(exchange, 200, Map.of("cancelled", omr.cancel(jobId)));
      } else if (path.equals("/api/omr/pdf-page") && method.equals("POST")) {
        pdfPage(exchange);
      } else if (path.equals("/api/omr/preview") && method.equals("POST")) {
        if (!"application/octet-stream"
            .equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
          sendJson(exchange, 415, Map.of("error", "Expected application/octet-stream"));
          return;
        }
        String encoded = exchange.getRequestHeaders().getFirst("X-Arpeon-Filename");
        if (encoded == null || encoded.length() > 1500)
          throw new IllegalArgumentException("Invalid OMR filename");
        String filename = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        String jobId = exchange.getRequestHeaders().getFirst("X-Arpeon-Job");
        if (jobId == null) jobId = java.util.UUID.randomUUID().toString();
        if (!jobId.matches("[a-fA-F0-9-]{36}"))
          throw new IllegalArgumentException("Invalid recognition jobId");
        byte[] content = exchange.getRequestBody().readNBytes(OmrInputValidator.MAX_PDF_BYTES + 1);
        if (content.length > OmrInputValidator.MAX_PDF_BYTES) {
          sendJson(exchange, 413, Map.of("error", "OMR input exceeds 64 MiB"));
          return;
        }
        if (!omr.available()) {
          sendJson(
              exchange,
              503,
              Map.of("error", "Reinstale o pacote completo do ARPEON para reconhecer partituras."));
          return;
        }
        try {
          sendJson(exchange, 200, omr.preview(content, filename, jobId));
        } catch (IOException failure) {
          // Native cancellation preserves interruption; the HTTP worker must clear it
          // before writing the terminal response through an interruptible socket.
          Thread.interrupted();
          sendJson(exchange, 422, Map.of("error", failure.getMessage()));
        }
      } else if (path.equals("/api/omr/accept") && method.equals("POST")) {
        JsonObject request = smallJson(exchange);
        if (request == null
            || !request.keySet().equals(Set.of("token", "acknowledge"))
            || !practiceBoolean(request, "acknowledge"))
          throw new IllegalArgumentException("Review and acknowledge OMR uncertainty");
        var recognized = omr.accept(practiceString(request, "token"));
        try {
          var result = workspace.openOmr(recognized);
          omr.clear();
          currentProjectId = null;
          lastTransportPollNanos = 0;
          sendJson(exchange, 200, result);
        } catch (IOException failure) {
          sendJson(exchange, 422, Map.of("error", failure.getMessage()));
        }
      } else if (path.equals("/api/import/url") && method.equals("POST")) {
        JsonObject request = smallJson(exchange);
        if (request == null || !request.keySet().equals(Set.of("url", "contact")))
          throw new IllegalArgumentException("Expected source URL and Wikimedia contact");
        try {
          sendJson(
              exchange,
              200,
              imports.previewWeb(
                  practiceString(request, "url"), practiceString(request, "contact")));
        } catch (IOException failure) {
          sendJson(exchange, 502, Map.of("error", "Could not fetch Wikimedia source"));
        }
      } else if (path.equals("/api/import/preview") && method.equals("POST")) {
        if (!"application/octet-stream"
            .equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
          sendJson(exchange, 415, Map.of("error", "Expected application/octet-stream"));
          return;
        }
        String encoded = exchange.getRequestHeaders().getFirst("X-Arpeon-Filename");
        if (encoded == null || encoded.length() > 768)
          throw new IllegalArgumentException("Invalid filename");
        String filename = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        String kind = exchange.getRequestHeaders().getFirst("X-Arpeon-Import-Kind");
        if (kind == null) throw new IllegalArgumentException("Select an import format");
        int max =
            "WAV".equals(kind)
                ? LocalImporters.MAX_WAV_BYTES
                : "MIDI".equals(kind)
                    ? LocalImporters.MAX_MIDI_BYTES
                    : LocalImporters.MAX_TEXT_BYTES;
        byte[] content = exchange.getRequestBody().readNBytes(max + 1);
        if (content.length > max) {
          sendJson(exchange, 413, Map.of("error", "Import exceeds the format size limit"));
          return;
        }
        try {
          sendJson(exchange, 200, imports.preview(content, filename, kind));
        } catch (IOException failure) {
          sendJson(exchange, 400, Map.of("error", failure.getMessage()));
        }
      } else if (path.equals("/api/import/notation") && method.equals("POST")) {
        JsonObject request = smallJson(exchange);
        if (request == null
            || !request.keySet().equals(Set.of("token", "fallbackOctave", "equalNoteTicks")))
          throw new IllegalArgumentException("Expected draft token, octave and duration");
        sendJson(
            exchange,
            200,
            Map.of(
                "xml",
                imports.notation(
                    practiceString(request, "token"),
                    practiceInt(request, "fallbackOctave"),
                    practiceInt(request, "equalNoteTicks"))));
      } else if (path.equals("/api/import/accept") && method.equals("POST")) {
        JsonObject request = smallJson(exchange);
        if (request == null
            || !request
                .keySet()
                .equals(Set.of("token", "fallbackOctave", "equalNoteTicks", "acknowledge"))
            || !practiceBoolean(request, "acknowledge"))
          throw new IllegalArgumentException("Review and acknowledge the import assumptions");
        var preview = imports.accept(practiceString(request, "token"));
        try {
          var result =
              workspace.openDraft(
                  preview.draft(),
                  preview.source(),
                  practiceInt(request, "fallbackOctave"),
                  practiceInt(request, "equalNoteTicks"));
          imports.clear();
          currentProjectId = null;
          lastTransportPollNanos = 0;
          sendJson(exchange, 200, result);
        } catch (IOException failure) {
          sendJson(exchange, 400, Map.of("error", failure.getMessage()));
        }
      } else if (path.equals("/api/score/open") && method.equals("POST")) {
        if (!"application/xml".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
          sendJson(exchange, 415, Map.of("error", "Expected application/xml"));
          return;
        }
        String encoded = exchange.getRequestHeaders().getFirst("X-Arpeon-Filename");
        if (encoded == null || encoded.length() > 768)
          throw new IllegalArgumentException("Invalid filename");
        String filename = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        ScoreWorkspace.View result;
        try {
          result = workspace.open(exchange.getRequestBody(), filename);
        } catch (IOException failure) {
          if (failure.getMessage() != null
              && failure.getMessage().contains("exceeds the 128 MiB limit")) {
            sendJson(exchange, 413, Map.of("error", "MusicXML exceeds the 128 MiB limit"));
            return;
          }
          sendJson(
              exchange,
              400,
              Map.of(
                  "error",
                  failure.getMessage() == null ? "Invalid MusicXML" : failure.getMessage()));
          return;
        }
        currentProjectId = null;
        lastTransportPollNanos = 0;
        sendJson(exchange, 200, result);
      } else if (path.equals("/api/score/open-mxl") && method.equals("POST")) {
        if (!"application/octet-stream"
            .equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
          sendJson(exchange, 415, Map.of("error", "Expected application/octet-stream"));
          return;
        }
        String encoded = exchange.getRequestHeaders().getFirst("X-Arpeon-Filename");
        if (encoded == null || encoded.length() > 768)
          throw new IllegalArgumentException("Invalid filename");
        String filename = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        byte[] content = exchange.getRequestBody().readNBytes(MusicXmlImporter.MAX_BYTES + 1);
        if (content.length > MusicXmlImporter.MAX_BYTES) {
          sendJson(exchange, 413, Map.of("error", "Compressed score exceeds the 128 MiB limit"));
          return;
        }
        ScoreWorkspace.View result;
        try {
          result = workspace.openMxl(content, filename);
        } catch (IOException failure) {
          sendJson(
              exchange,
              400,
              Map.of(
                  "error",
                  failure.getMessage() == null
                      ? "Invalid compressed score"
                      : failure.getMessage()));
          return;
        }
        currentProjectId = null;
        lastTransportPollNanos = 0;
        sendJson(exchange, 200, result);
      } else if (path.equals("/api/score") && method.equals("GET")) {
        sendJson(exchange, 200, workspace.snapshot());
      } else if (path.equals("/api/score/measure-events") && method.equals("GET")) {
        var query = parseQuery(exchange.getRequestURI());
        if (!query.keySet().equals(Set.of("partId", "measureIndex")))
          throw new IllegalArgumentException("Expected partId and measureIndex");
        int measureIndex;
        try {
          measureIndex = Integer.parseInt(query.get("measureIndex"));
        } catch (NumberFormatException invalid) {
          throw new IllegalArgumentException("Invalid measureIndex", invalid);
        }
        sendJson(exchange, 200, workspace.measureEvents(query.get("partId"), measureIndex));
      } else if (path.equals("/api/score/export") && method.equals("GET")) {
        byte[] xml = workspace.exportXml().getBytes(StandardCharsets.UTF_8);
        exchange
            .getResponseHeaders()
            .set("Content-Type", "application/vnd.recordare.musicxml+xml; charset=utf-8");
        exchange
            .getResponseHeaders()
            .set("Content-Disposition", "attachment; filename=\"arpeon-editado.musicxml\"");
        exchange.sendResponseHeaders(200, xml.length);
        exchange.getResponseBody().write(xml);
      } else if (path.startsWith("/api/score/edit/") && method.equals("POST")) {
        if (!"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
          sendJson(exchange, 415, Map.of("error", "Expected application/json"));
          return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) {
          sendJson(exchange, 413, Map.of("error", "Request is too large"));
          return;
        }
        var request = json.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        var result = workspace.edit(path.substring("/api/score/edit/".length()), request);
        lastTransportPollNanos = 0;
        sendJson(exchange, 200, result);
      } else if (path.equals("/api/practice/create") && method.equals("POST")) {
        JsonObject request = smallJson(exchange);
        if (request == null
            || !request
                .keySet()
                .equals(
                    Set.of(
                        "kind", "keyId", "octave", "scaleType", "bpm", "loop", "progressiveStep")))
          throw new IllegalArgumentException("Unexpected exercise parameters");
        sendJson(
            exchange,
            200,
            practice.create(
                practiceString(request, "kind"),
                practiceString(request, "keyId"),
                practiceInt(request, "octave"),
                practiceString(request, "scaleType"),
                practiceInt(request, "bpm"),
                practiceBoolean(request, "loop"),
                practiceInt(request, "progressiveStep")));
      } else if (path.equals("/api/practice") && method.equals("GET")) {
        lastPracticePollNanos = System.nanoTime();
        sendJson(exchange, 200, practice.snapshot());
      } else if (path.equals("/api/practice/start") && method.equals("POST")) {
        emptyJson(exchange);
        requireNoAnalysis();
        if (captureActive() && !captureOwner(exchange).equals(tunerOwner))
          throw new IllegalStateException(
              "Desligue a captura da outra área antes de iniciar a prática.");
        workspace.stopIfPlaying();
        sendJson(exchange, 200, practice.start());
      } else if (path.equals("/api/practice/observe") && method.equals("GET")) {
        synchronized (tuner) {
          if (analysis.active()
              || (captureActive() && !captureOwner(exchange).equals(tunerOwner))) {
            sendJson(
                exchange,
                200,
                Map.of(
                    "practice",
                    practice.snapshot(),
                    "observation",
                    org.arpeon.tuner.TunerObservation.inactive(
                        org.arpeon.tuner.TunerObservation.State.IDLE,
                        440,
                        "Captura em outra área")));
            return;
          }
          lastPollNanos = System.nanoTime();
          lastPracticePollNanos = lastPollNanos;
          sendJson(
              exchange,
              200,
              Map.of(
                  "practice",
                  practice.observe(tuner.observation()),
                  "observation",
                  tuner.observation()));
        }
      } else if (path.equals("/api/practice/tap") && method.equals("POST")) {
        emptyJson(exchange);
        sendJson(exchange, 200, practice.tap());
      } else if (path.equals("/api/practice/history") && method.equals("GET")) {
        sendJson(exchange, 200, history(exchange.getRequestURI()));
      } else if (path.equals("/api/practice/finish") && method.equals("POST")) {
        emptyJson(exchange);
        var summary = practice.finish();
        try {
          practiceHistory.add(summary);
          practice.markSaved();
          sendJson(exchange, 200, Map.of("summary", summary));
        } catch (IOException failure) {
          throw new IllegalStateException("Could not save practice history", failure);
        }
      } else if (path.startsWith("/api/practice/playback/") && method.equals("POST")) {
        emptyJson(exchange);
        if (path.endsWith("/play")) {
          requirePlaybackAvailable();
          workspace.stopIfPlaying();
        }
        lastPracticePollNanos = System.nanoTime();
        sendJson(
            exchange, 200, practice.playback(path.substring("/api/practice/playback/".length())));
      } else if (path.equals("/api/transport") && method.equals("GET")) {
        lastTransportPollNanos = System.nanoTime();
        sendJson(exchange, 200, workspace.transport());
      } else if (path.startsWith("/api/transport/") && method.equals("POST")) {
        if (!"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
          sendJson(exchange, 415, Map.of("error", "Expected application/json"));
          return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(1025);
        if (body.length > 1024) {
          sendJson(exchange, 413, Map.of("error", "Request is too large"));
          return;
        }
        var request = json.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        if (path.equals("/api/transport/play")) {
          if (request == null || !request.keySet().isEmpty())
            throw new IllegalArgumentException("Unexpected playback parameters");
          if (!workspace.isOpen())
            throw new IllegalStateException("Abra uma partitura antes de reproduzir.");
          requirePlaybackAvailable();
          practice.stopIfPlaying();
        }
        var result = workspace.command(path.substring("/api/transport/".length()), request);
        lastTransportPollNanos = System.nanoTime();
        sendJson(exchange, 200, result.transport());
      } else if (path.equals("/api/devices") && method.equals("GET"))
        sendJson(exchange, 200, tuner.devices());
      else if (path.equals("/api/analysis") && method.equals("GET"))
        sendJson(exchange, 200, analysis.view());
      else if (path.equals("/api/analysis/start") && method.equals("POST")) {
        JsonObject request = smallJson(exchange);
        if (request == null
            || !(request
                    .keySet()
                    .equals(
                        Set.of(
                            "partId",
                            "deviceId",
                            "referenceHz",
                            "consent",
                            "alignmentWindowMs",
                            "timingToleranceMs",
                            "pitchToleranceCents",
                            "rhythmTolerancePercent",
                            "latencyCompensationMs"))
                || request
                    .keySet()
                    .equals(
                        Set.of(
                            "partId",
                            "deviceId",
                            "referenceHz",
                            "consent",
                            "alignmentWindowMs",
                            "timingToleranceMs",
                            "pitchToleranceCents",
                            "rhythmTolerancePercent",
                            "latencyCompensationMs",
                            "mode"))))
          throw new IllegalArgumentException("Unexpected analysis parameters");
        String mode = request.has("mode") ? practiceString(request, "mode") : "ANALYSIS";
        if (!mode.equals("ANALYSIS") && !mode.equals("FOLLOW"))
          throw new IllegalArgumentException("Unknown analysis mode");
        var reference = request.get("referenceHz");
        if (reference == null
            || !reference.isJsonPrimitive()
            || !reference.getAsJsonPrimitive().isNumber())
          throw new IllegalArgumentException("Expected tuning reference");
        var selected =
            new AnalysisConfig(
                practiceInt(request, "alignmentWindowMs"),
                practiceInt(request, "timingToleranceMs"),
                practiceInt(request, "pitchToleranceCents"),
                practiceInt(request, "rhythmTolerancePercent"),
                practiceInt(request, "latencyCompensationMs"));
        requireNoAnalysis();
        if (practice.activeAttempt())
          throw new IllegalStateException(
              "Conclua a tentativa de prática antes de iniciar a análise.");
        String owner = captureOwner(exchange);
        synchronized (tuner) {
          requireCaptureAvailable(owner);
          if (!practiceBoolean(request, "consent"))
            throw new IllegalArgumentException("É necessário autorizar o microfone.");
          workspace.pauseIfPlaying();
          practice.pauseIfPlaying();
          var result =
              analysis.start(
                  practiceString(request, "partId"),
                  practiceString(request, "deviceId"),
                  reference.getAsDouble(),
                  practiceBoolean(request, "consent"),
                  selected,
                  mode.equals("FOLLOW"));
          analysisOwner = owner;
          tunerOwner = null;
          analysisProjectId = currentProjectId;
          lastPersistedAnalysisStart = Long.MIN_VALUE;
          lastPollNanos = System.nanoTime();
          sendJson(exchange, 200, result);
        }
      } else if (path.equals("/api/analysis/observe") && method.equals("GET")) {
        synchronized (tuner) {
          if (analysis.active() && !captureOwner(exchange).equals(analysisOwner))
            throw new IllegalStateException("A captura pertence a outra janela de análise.");
          lastPollNanos = System.nanoTime();
          var result = persistAnalysisReport(analysis.observe());
          sendJson(exchange, 200, result);
        }
      } else if (path.equals("/api/analysis/finish") && method.equals("POST")) {
        emptyJson(exchange);
        synchronized (tuner) {
          if (!captureOwner(exchange).equals(analysisOwner))
            throw new IllegalStateException("A captura pertence a outra janela de análise.");
          var result = persistAnalysisReport(analysis.finish());
          lastPollNanos = 0;
          sendJson(exchange, 200, result);
        }
      } else if (path.equals("/api/analysis/stop") && method.equals("POST")) {
        emptyJson(exchange);
        synchronized (tuner) {
          if (!captureOwner(exchange).equals(analysisOwner)) {
            sendJson(exchange, 200, analysis.view());
            return;
          }
          var result = analysis.stop();
          lastPollNanos = 0;
          sendJson(exchange, 200, result);
        }
      } else if (path.equals("/api/tuner") && method.equals("GET")) {
        synchronized (tuner) {
          if (analysis.active() || (captureActive() && !captureOwner(exchange).equals(tunerOwner)))
            throw new IllegalStateException(
                "O microfone está em uso por outra captura. Encerre-a antes de continuar.");
          lastPollNanos = System.nanoTime();
        }
        sendJson(exchange, 200, tuner.observation());
      } else if (path.equals("/api/tuner/stop") && method.equals("POST")) {
        synchronized (tuner) {
          if (!analysis.active() && captureOwner(exchange).equals(tunerOwner)) {
            tuner.stop();
            tunerOwner = null;
            lastPollNanos = 0;
          }
        }
        sendJson(exchange, 200, tuner.observation());
      } else if (path.equals("/api/tuner/start") && method.equals("POST")) {
        if (!"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
          sendJson(exchange, 415, Map.of("error", "Expected application/json"));
          return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(1025);
        if (body.length > 1024) {
          sendJson(exchange, 413, Map.of("error", "Request is too large"));
          return;
        }
        JsonObject request =
            json.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        if (request == null
            || !request.keySet().equals(Set.of("deviceId", "referenceHz", "consent"))
            || !request.get("consent").isJsonPrimitive()
            || !request.getAsJsonPrimitive("consent").isBoolean()
            || !request.get("consent").getAsBoolean()
            || !request.get("deviceId").isJsonPrimitive()
            || !request.getAsJsonPrimitive("deviceId").isString()
            || !request.get("referenceHz").isJsonPrimitive()
            || !request.getAsJsonPrimitive("referenceHz").isNumber())
          throw new IllegalArgumentException(
              "Input, reference and explicit microphone consent are required");
        synchronized (tuner) {
          requireNoAnalysis();
          String owner = captureOwner(exchange);
          requireCaptureAvailable(owner);
          var tuning = new TuningReference(request.get("referenceHz").getAsDouble());
          workspace.pauseIfPlaying();
          practice.pauseIfPlaying();
          tuner.start(request.get("deviceId").getAsString(), tuning);
          tunerOwner = owner;
          lastPollNanos = System.nanoTime();
        }
        sendJson(exchange, 200, tuner.observation());
      } else sendJson(exchange, 405, Map.of("error", "Unsupported API operation"));
    } catch (JsonParseException | IllegalArgumentException | ArithmeticException failure) {
      sendJson(
          exchange,
          400,
          Map.of("error", "Invalid or unsupported request: " + failure.getMessage()));
    } catch (IllegalStateException | SecurityException failure) {
      sendJson(exchange, 409, Map.of("error", failure.getMessage()));
    } catch (IOException failure) {
      System.getLogger(DesktopServer.class.getName())
          .log(System.Logger.Level.ERROR, "Local data operation failed: " + path, failure);
      sendJson(
          exchange,
          500,
          Map.of(
              "error",
              "Não foi possível acessar os dados locais. Confira o espaço livre e as permissões da"
                  + " pasta do usuário e tente novamente."));
    } catch (RuntimeException failure) {
      System.getLogger(DesktopServer.class.getName())
          .log(System.Logger.Level.ERROR, "Unexpected local API failure", failure);
      sendJson(
          exchange,
          500,
          Map.of(
              "error",
              path.startsWith("/api/omr/")
                  ? "Falha inesperada no reconhecimento. Consulte a janela do ARPEON."
                  : "Falha inesperada no aplicativo local. Consulte a janela do ARPEON."));
    }
  }

  private static String captureOwner(HttpExchange exchange) {
    String owner = exchange.getRequestHeaders().getFirst("X-Arpeon-Capture-Owner");
    if (owner == null) return "legacy";
    if (!owner.matches("[a-zA-Z0-9-]{1,64}"))
      throw new IllegalArgumentException("Identificador de captura inválido.");
    return owner;
  }

  private boolean captureActive() {
    var state = tuner.observation().state();
    return state != org.arpeon.tuner.TunerObservation.State.IDLE
        && state != org.arpeon.tuner.TunerObservation.State.ERROR;
  }

  private void requireNoAnalysis() {
    if (analysis.active())
      throw new IllegalStateException("Conclua ou cancele a análise antes de mudar o áudio.");
  }

  private void requirePlaybackAvailable() {
    requireNoAnalysis();
    if (practice.activeAttempt())
      throw new IllegalStateException(
          "Conclua a tentativa de prática antes de reproduzir a referência.");
    if (captureActive())
      throw new IllegalStateException(
          "Desligue o microfone antes de reproduzir para evitar que a referência seja capturada.");
  }

  private void requireCaptureAvailable(String owner) {
    if (captureActive() && !owner.equals(tunerOwner))
      throw new IllegalStateException(
          "O microfone está em uso em outra área. Desligue a captura atual antes de iniciar"
              + " outra.");
  }

  private static Map<String, String> parseQuery(URI uri) {
    String raw = uri.getRawQuery();
    if (raw == null || raw.isEmpty()) return Map.of();
    var values = new java.util.HashMap<String, String>();
    for (String pair : raw.split("&", -1)) {
      int separator = pair.indexOf('=');
      if (separator <= 0) throw new IllegalArgumentException("Invalid query parameter");
      String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
      if (values.putIfAbsent(key, value) != null)
        throw new IllegalArgumentException("Duplicate query parameter");
    }
    return Map.copyOf(values);
  }

  private ProjectStore.Saved saveProject(String id, ProjectStore.Data data, boolean autosave)
      throws IOException {
    if (!autosave) return projects.save(id, data);
    try {
      return autosaveWriter.submit(() -> projects.save(id, data)).get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Autosave was interrupted", interrupted);
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof IOException ioFailure) throw ioFailure;
      if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
      throw new IOException("Autosave failed", cause);
    }
  }

  private synchronized AnalysisWorkspace.View persistAnalysisReport(AnalysisWorkspace.View view) {
    if (view.report() == null
        || !"ANALYSIS".equals(view.mode())
        || view.startsAtEpochMs() == null
        || view.startsAtEpochMs() == lastPersistedAnalysisStart) return view;
    try {
      analysisHistory.add(
          new AnalysisHistory.Entry(
              java.util.UUID.randomUUID().toString(),
              analysisProjectId,
              view.sha256(),
              view.revision(),
              view.title(),
              view.filename(),
              view.partId(),
              view.partName(),
              view.mode(),
              Instant.now().toString(),
              view.report(),
              view.study()));
      lastPersistedAnalysisStart = view.startsAtEpochMs();
      return view;
    } catch (IOException failure) {
      System.getLogger(DesktopServer.class.getName())
          .log(
              System.Logger.Level.WARNING, "Completed analysis report could not be saved", failure);
      return new AnalysisWorkspace.View(
          view.status(),
          view.mode(),
          view.title(),
          view.filename(),
          view.sha256(),
          view.revision(),
          view.partId(),
          view.partName(),
          view.soundingSemitones(),
          view.expectedCount(),
          view.durationMs(),
          view.initialBpm(),
          view.startsAtEpochMs(),
          view.elapsedMs(),
          view.observedOnsets(),
          view.expected(),
          view.expectedTicks(),
          view.follow(),
          view.config(),
          view.report(),
          view.message() + " O relatório está disponível apenas nesta sessão porque não foi salvo.",
          view.study());
    }
  }

  private PracticeHistory.Page history(URI uri) {
    var query = parseQuery(uri);
    if (!Set.of("page", "pageSize", "kind", "outcome", "from", "until").containsAll(query.keySet()))
      throw new IllegalArgumentException("Filtro de histórico desconhecido.");
    try {
      return practiceHistory.page(
          Integer.parseInt(query.getOrDefault("page", "0")),
          Integer.parseInt(query.getOrDefault("pageSize", "20")),
          query.getOrDefault("kind", ""),
          query.getOrDefault("outcome", ""),
          query.containsKey("from") ? java.time.Instant.parse(query.get("from")) : null,
          query.containsKey("until") ? java.time.Instant.parse(query.get("until")) : null);
    } catch (NumberFormatException | java.time.format.DateTimeParseException failure) {
      throw new IllegalArgumentException("Página ou período do histórico inválido.");
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Não foi possível ler o histórico local. Tente novamente.", failure);
    }
  }

  private JsonObject smallJson(HttpExchange exchange) throws IOException {
    if (!"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type")))
      throw new IllegalArgumentException("Expected application/json");
    byte[] body = exchange.getRequestBody().readNBytes(1025);
    if (body.length > 1024) throw new IllegalArgumentException("Request is too large");
    return json.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
  }

  private void emptyJson(HttpExchange exchange) throws IOException {
    JsonObject request = smallJson(exchange);
    if (request == null || !request.keySet().isEmpty())
      throw new IllegalArgumentException("Unexpected practice parameters");
  }

  private static String practiceString(JsonObject request, String name) {
    var value = request.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected text: " + name);
    return value.getAsString();
  }

  private static int practiceInt(JsonObject request, String name) {
    var value = request.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Expected integer: " + name);
    return value.getAsBigDecimal().intValueExact();
  }

  private static boolean practiceBoolean(JsonObject request, String name) {
    var value = request.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException("Expected boolean: " + name);
    return value.getAsBoolean();
  }

  private void staticFile(HttpExchange exchange, String path) throws IOException {
    if (!exchange.getRequestMethod().equals("GET")) {
      sendJson(exchange, 405, Map.of("error", "Use GET"));
      return;
    }
    if (path.equals("/")) path = "/index.html";
    if (!path.matches("/[A-Za-z0-9_./-]+") || path.contains("..") || path.contains("//")) {
      sendJson(exchange, 404, Map.of("error", "Not found"));
      return;
    }
    String type;
    if (path.endsWith(".html")) type = "text/html; charset=utf-8";
    else if (path.endsWith(".js")) type = "text/javascript; charset=utf-8";
    else if (path.endsWith(".css")) type = "text/css; charset=utf-8";
    else if (path.endsWith(".woff2")) type = "font/woff2";
    else if (path.endsWith(".jpg")) type = "image/jpeg";
    else if (path.endsWith(".svg")) type = "image/svg+xml";
    else if (path.endsWith(".png")) type = "image/png";
    else if (path.endsWith(".ico")) type = "image/x-icon";
    else if (path.endsWith(".zip")) type = "application/zip";
    else if (path.endsWith(".txt")) type = "text/plain; charset=utf-8";
    else {
      sendJson(exchange, 404, Map.of("error", "Not found"));
      return;
    }
    try (var resource = getClass().getResourceAsStream("/web" + path)) {
      if (resource == null) {
        sendJson(exchange, 404, Map.of("error", "Not found. Build the frontend first."));
        return;
      }
      exchange.getResponseHeaders().set("Content-Type", type);
      exchange.sendResponseHeaders(200, 0);
      resource.transferTo(exchange.getResponseBody());
    }
  }

  private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, 0);
    try (var writer = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8)) {
      json.toJson(value, writer);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    omr.close();
    watchdog.shutdownNow();
    server.stop(0);
    requests.shutdownNow();
    autosaveWriter.shutdown();
    try {
      if (!autosaveWriter.awaitTermination(5, TimeUnit.SECONDS)) autosaveWriter.shutdownNow();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      autosaveWriter.shutdownNow();
    }
    tuner.close();
    workspace.close();
    practice.close();
  }
}
