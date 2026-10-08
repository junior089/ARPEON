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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.arpeon.importer.LocalImporters;
import org.arpeon.musicxml.MusicXmlImporter;
import org.arpeon.tuner.TunerService;
import org.arpeon.tuner.TuningReference;

/** Same-origin loopback bridge. No audio samples or filesystem paths cross this boundary. */
public final class DesktopServer implements AutoCloseable {
  private final TunerService tuner;
  private final ScoreWorkspace workspace = new ScoreWorkspace();
  private final ImportWorkspace imports = new ImportWorkspace();
  private final PracticeWorkspace practice = new PracticeWorkspace();
  private final PracticeHistory practiceHistory;
  private final HttpServer server;
  private final ExecutorService requests = Executors.newFixedThreadPool(4);
  private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
  private final String token;
  private final String origin;
  private volatile long lastPollNanos;
  private volatile long lastTransportPollNanos;
  private volatile long lastPracticePollNanos;
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
    this.tuner = tuner;
    this.practiceHistory = practiceHistory;
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

  public void start() {
    server.start();
    watchdog.scheduleWithFixedDelay(
        () -> {
          try {
            synchronized (tuner) {
              if (lastPollNanos != 0
                  && System.nanoTime() - lastPollNanos > TimeUnit.SECONDS.toNanos(5)) {
                tuner.stop();
                lastPollNanos = 0;
              }
              tuner.checkCaptureHealth();
              if (lastTransportPollNanos != 0
                  && System.nanoTime() - lastTransportPollNanos > TimeUnit.SECONDS.toNanos(5)) {
                workspace.stopIfPlaying();
                lastTransportPollNanos = 0;
              }
              if (lastPracticePollNanos != 0
                  && System.nanoTime() - lastPracticePollNanos > TimeUnit.SECONDS.toNanos(5)) {
                practice.stopIfPlaying();
                lastPracticePollNanos = 0;
              }
            }
          } catch (RuntimeException failure) {
            System.getLogger(DesktopServer.class.getName())
                .log(System.Logger.Level.ERROR, "Audio lifecycle watchdog failed");
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
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
              + " 'self'; img-src 'self' data:; font-src 'self'; object-src 'none'; base-uri"
              + " 'self'; frame-ancestors 'none'");
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
      // A closed tab can disconnect during a response; the microphone lease still expires.
      System.getLogger(DesktopServer.class.getName())
          .log(System.Logger.Level.DEBUG, "Local UI connection closed");
    }
  }

  private void api(HttpExchange exchange, String path) throws IOException {
    String method = exchange.getRequestMethod();
    try {
      if (path.equals("/api/import/preview") && method.equals("POST")) {
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
      } else if (path.equals("/api/import/accept") && method.equals("POST")) {
        JsonObject request = smallJson(exchange);
        if (request == null
            || !request
                .keySet()
                .equals(Set.of("token", "fallbackOctave", "equalNoteTicks", "acknowledge"))
            || !practiceBoolean(request, "acknowledge"))
          throw new IllegalArgumentException("Review and acknowledge the import assumptions");
        var draft = imports.accept(practiceString(request, "token"));
        try {
          var result =
              workspace.openDraft(
                  draft,
                  practiceInt(request, "fallbackOctave"),
                  practiceInt(request, "equalNoteTicks"));
          imports.clear();
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
        byte[] content = exchange.getRequestBody().readNBytes(MusicXmlImporter.MAX_BYTES + 1);
        if (content.length > MusicXmlImporter.MAX_BYTES) {
          sendJson(exchange, 413, Map.of("error", "MusicXML exceeds the 1 MiB limit"));
          return;
        }
        ScoreWorkspace.View result;
        try {
          result = workspace.open(new java.io.ByteArrayInputStream(content), filename);
        } catch (IOException failure) {
          sendJson(
              exchange,
              400,
              Map.of(
                  "error",
                  failure.getMessage() == null ? "Invalid MusicXML" : failure.getMessage()));
          return;
        }
        lastTransportPollNanos = 0;
        sendJson(exchange, 200, result);
      } else if (path.equals("/api/score") && method.equals("GET")) {
        sendJson(exchange, 200, workspace.snapshot());
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
        workspace.stopIfPlaying();
        sendJson(exchange, 200, practice.start());
      } else if (path.equals("/api/practice/observe") && method.equals("GET")) {
        synchronized (tuner) {
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
        sendJson(exchange, 200, history());
      } else if (path.equals("/api/practice/finish") && method.equals("POST")) {
        emptyJson(exchange);
        var summary = practice.finish();
        try {
          var saved = practiceHistory.add(summary);
          practice.markSaved();
          sendJson(exchange, 200, Map.of("summary", summary, "history", saved));
        } catch (IOException failure) {
          throw new IllegalStateException("Could not save practice history", failure);
        }
      } else if (path.startsWith("/api/practice/playback/") && method.equals("POST")) {
        emptyJson(exchange);
        if (path.endsWith("/play")) workspace.stopIfPlaying();
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
        if (path.equals("/api/transport/play")) practice.stopIfPlaying();
        var result = workspace.command(path.substring("/api/transport/".length()), request);
        lastTransportPollNanos = System.nanoTime();
        sendJson(exchange, 200, result.transport());
      } else if (path.equals("/api/devices") && method.equals("GET"))
        sendJson(exchange, 200, tuner.devices());
      else if (path.equals("/api/tuner") && method.equals("GET")) {
        synchronized (tuner) {
          lastPollNanos = System.nanoTime();
        }
        sendJson(exchange, 200, tuner.observation());
      } else if (path.equals("/api/tuner/stop") && method.equals("POST")) {
        synchronized (tuner) {
          tuner.stop();
          lastPollNanos = 0;
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
          tuner.start(
              request.get("deviceId").getAsString(),
              new TuningReference(request.get("referenceHz").getAsDouble()));
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
    }
  }

  private java.util.List<PracticeWorkspace.Summary> history() {
    try {
      return practiceHistory.list();
    } catch (IOException failure) {
      throw new IllegalStateException("Could not read practice history", failure);
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
    byte[] body = json.toJson(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  @Override
  public void close() {
    watchdog.shutdownNow();
    server.stop(0);
    requests.shutdownNow();
    tuner.close();
    workspace.close();
    practice.close();
  }
}
