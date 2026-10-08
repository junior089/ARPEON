package org.arpeon.desktop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.arpeon.importer.ImportDraft;
import org.arpeon.importer.LocalImporters;

/** One Commons file-page URL via the official imageinfo API; never crawls page HTML. */
final class WikimediaCommonsAdapter {
  record Response(int status, String contentType, byte[] body, String retryAfter) {}

  @FunctionalInterface
  interface Fetcher {
    Response get(URI uri, String userAgent, int maxBytes) throws IOException;
  }

  record Result(ImportDraft draft, ImportWorkspace.Source source) {}

  private static final String API = "https://commons.wikimedia.org/w/api.php";
  private static final int MAX_METADATA_BYTES = 131_072;
  private static final long COOLDOWN_NANOS = Duration.ofSeconds(2).toNanos();
  private final Fetcher fetcher;
  private final java.util.function.Supplier<Instant> clock;
  private long nextAllowedNanos;

  WikimediaCommonsAdapter() {
    this(new LiveFetcher(), Instant::now);
  }

  WikimediaCommonsAdapter(Fetcher fetcher, java.util.function.Supplier<Instant> clock) {
    this.fetcher = fetcher;
    this.clock = clock;
  }

  synchronized Result preview(String requestedUrl, String contact) throws IOException {
    URI page;
    try {
      page = URI.create(requestedUrl);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Invalid source URL", failure);
    }
    SourceRegistry.Provider provider = SourceRegistry.eligible(page);
    if (!provider.id().equals("wikimedia-commons"))
      throw new IllegalArgumentException("No adapter for this provider");
    String title = fileTitle(page);
    String userAgent = userAgent(contact);
    if (System.nanoTime() - nextAllowedNanos < 0)
      throw new IllegalStateException("Wait before requesting another Wikimedia Commons file");
    nextAllowedNanos = System.nanoTime() + COOLDOWN_NANOS;
    URI api = URI.create(API + "?action=query&format=json&formatversion=2&prop=imageinfo"
        + "&iiprop=url%7Csize%7Cmime%7Csha1%7Cextmetadata&iiextmetadatalanguage=en"
        + "&maxlag=5&titles=" + URLEncoder.encode("File:" + title, StandardCharsets.UTF_8));
    Response metadata = fetcher.get(api, userAgent, MAX_METADATA_BYTES);
    requireResponse(metadata, "application/json", MAX_METADATA_BYTES);
    JsonObject root;
    try {
      root = JsonParser.parseString(new String(metadata.body(), StandardCharsets.UTF_8))
          .getAsJsonObject();
      if (root.has("error")) throw new IllegalArgumentException("Wikimedia API reported an error or throttling");
      var pages = root.getAsJsonObject("query").getAsJsonArray("pages");
      if (pages.size() != 1) throw new IllegalArgumentException("Expected one Wikimedia file");
      JsonObject pageInfo = pages.get(0).getAsJsonObject();
      if (pageInfo.has("missing") || !pageInfo.get("title").getAsString()
          .replace('_', ' ').equalsIgnoreCase(("File:" + title).replace('_', ' ')))
        throw new IllegalArgumentException("Requested Wikimedia file was not found");
      var infoArray = pageInfo.getAsJsonArray("imageinfo");
      if (infoArray.size() != 1) throw new IllegalArgumentException("Missing Wikimedia file metadata");
      JsonObject info = infoArray.get(0).getAsJsonObject();
      long size = info.get("size").getAsLong();
      if (size < 1 || size > LocalImporters.MAX_MIDI_BYTES)
        throw new IllegalArgumentException("Wikimedia MIDI exceeds the 1 MiB limit");
      String mime = info.get("mime").getAsString().toLowerCase(Locale.ROOT);
      if (!List.of("audio/midi", "audio/x-midi", "audio/mid").contains(mime))
        throw new IllegalArgumentException("Wikimedia file is not MIDI");
      URI media = URI.create(info.get("url").getAsString());
      if (!validMediaUrl(media)) throw new IllegalArgumentException("Unapproved Wikimedia media URL");
      Response downloaded = fetcher.get(media, userAgent, LocalImporters.MAX_MIDI_BYTES);
      requireResponse(downloaded, mime, LocalImporters.MAX_MIDI_BYTES);
      if (downloaded.body().length != size)
        throw new IllegalArgumentException("Wikimedia file size changed during import");
      String sha1 = info.get("sha1").getAsString();
      if (!sha1.matches("[a-fA-F0-9]{40}") || !sha1.equalsIgnoreCase(sha1(downloaded.body())))
        throw new IllegalArgumentException("Wikimedia file checksum did not match metadata");
      ImportDraft draft = LocalImporters.midi(downloaded.body(), title);
      JsonObject ext = info.has("extmetadata") ? info.getAsJsonObject("extmetadata") : null;
      String license = metadataValue(ext, "LicenseShortName");
      String licenseUrl = metadataValue(ext, "LicenseUrl");
      if (!licenseUrl.startsWith("https://")) licenseUrl = "";
      var source = new ImportWorkspace.Source(
          provider.id(), page.toString(), clock.get().toString(),
          license.isBlank() ? "UNKNOWN" : "DECLARED_REVIEW_REQUIRED",
          license.isBlank() ? "Licença não informada pela API" : license,
          licenseUrl,
          List.of("Wikimedia Commons imageinfo API", "MIDI local-midi-1"));
      return new Result(draft, source);
    } catch (IllegalStateException | NullPointerException | ClassCastException failure) {
      throw new IllegalArgumentException("Wikimedia API response format changed", failure);
    }
  }

  private void requireResponse(Response response, String expectedType, int maxBytes) {
    if (response.status() == 429 || response.status() == 503) {
      long delay = 60;
      try {
        delay = Math.clamp(Long.parseLong(response.retryAfter()), 1, 3600);
      } catch (RuntimeException ignored) {
        // A nonnumeric Retry-After receives a conservative one-minute pause.
      }
      nextAllowedNanos = System.nanoTime() + Duration.ofSeconds(delay).toNanos();
      throw new IllegalStateException("Wikimedia requested throttling; retry later");
    }
    if (response.status() != 200) throw new IllegalArgumentException("Wikimedia request failed: HTTP " + response.status());
    if (response.body().length < 1 || response.body().length > maxBytes)
      throw new IllegalArgumentException("Wikimedia response exceeds size limit");
    String type = response.contentType() == null ? "" : response.contentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (!type.equals(expectedType)) throw new IllegalArgumentException("Unexpected Wikimedia content type");
  }

  private static String fileTitle(URI page) {
    String raw = page.getRawPath();
    if (raw == null || !raw.startsWith("/wiki/") || raw.length() > 230)
      throw new IllegalArgumentException("Use a Wikimedia Commons File: page URL");
    String path = java.net.URLDecoder.decode(raw.substring(6), StandardCharsets.UTF_8);
    if (!path.startsWith("File:") || path.length() > 155)
      throw new IllegalArgumentException("Use a Wikimedia Commons File: page URL");
    String title = path.substring(5);
    if (!title.matches("[^/\\\\:?#%\\p{Cntrl}]{1,150}\\.(?i:mid|midi)"))
      throw new IllegalArgumentException("Only a single Commons MIDI file page is supported");
    return title;
  }

  private static boolean validMediaUrl(URI uri) {
    return "https".equalsIgnoreCase(uri.getScheme())
        && "upload.wikimedia.org".equalsIgnoreCase(uri.getHost())
        && uri.getPort() == -1 && uri.getUserInfo() == null
        && uri.getRawQuery() == null && uri.getRawFragment() == null
        && uri.getRawPath() != null
        && uri.getRawPath().startsWith("/wikipedia/commons/")
        && uri.getRawPath().toLowerCase(Locale.ROOT).matches(".*\\.(mid|midi)");
  }

  private static String userAgent(String contact) {
    if (contact == null || contact.length() > 120
        || !(contact.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
          || contact.matches("https://[A-Za-z0-9.-]+(?:/[A-Za-z0-9._~/-]*)?")))
      throw new IllegalArgumentException("Provide a contact email or HTTPS page for Wikimedia API identification");
    return "ARPEON/0.1 (" + contact + ") JavaHttpClient";
  }

  private static String metadataValue(JsonObject ext, String key) {
    if (ext == null || !ext.has(key)) return "";
    String value = ext.getAsJsonObject(key).get("value").getAsString();
    return value.length() > 200 ? "" : value.replaceAll("<[^>]*>", "").trim();
  }

  private static String sha1(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static final class LiveFetcher implements Fetcher {
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public Response get(URI uri, String userAgent, int maxBytes) throws IOException {
      var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
          .header("User-Agent", userAgent).header("Accept", "application/json, audio/midi")
          .GET().build();
      try {
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
          if (response.headers().firstValueAsLong("Content-Length").orElse(0) > maxBytes)
            throw new IllegalArgumentException("Wikimedia response exceeds size limit");
          return new Response(response.statusCode(),
              response.headers().firstValue("Content-Type").orElse(""),
              stream.readNBytes(maxBytes + 1),
              response.headers().firstValue("Retry-After").orElse(""));
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IOException("Wikimedia request interrupted", failure);
      }
    }
  }
}
