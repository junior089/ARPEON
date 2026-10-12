package org.arpeon.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** Small, versioned UI preferences independent of the launcher's loopback port. */
final class PreferencesStore {
  private static final int MAX_BYTES = 4096;
  private static final Set<String> FIELDS =
      Set.of(
          "schemaVersion",
          "theme",
          "explorer",
          "inspector",
          "inspectorWidth",
          "zoom",
          "mode",
          "scoreDark");
  private static final Set<String> READING_FIELDS =
      Set.of(
          "readingEnabled", "readingLanguage", "readingOctave", "readingScope", "followFingering");

  record Settings(
      int schemaVersion,
      String theme,
      boolean explorer,
      boolean inspector,
      int inspectorWidth,
      int zoom,
      String mode,
      boolean scoreDark,
      boolean readingEnabled,
      String readingLanguage,
      boolean readingOctave,
      String readingScope,
      boolean followFingering) {}

  private final Path file;
  private final Gson json = new GsonBuilder().setStrictness(Strictness.STRICT).create();

  PreferencesStore(Path file) {
    this.file = file.toAbsolutePath().normalize();
  }

  static PreferencesStore defaultStore() {
    return new PreferencesStore(UserDirectories.data().resolve("preferences.json"));
  }

  synchronized Settings load() throws IOException {
    if (!Files.exists(file)) return null;
    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > MAX_BYTES)
      return null;
    try {
      return parse(json.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class));
    } catch (JsonParseException | IllegalArgumentException | ArithmeticException invalid) {
      System.getLogger(PreferencesStore.class.getName())
          .log(System.Logger.Level.WARNING, "Ignoring invalid local UI preferences");
      return null;
    }
  }

  synchronized Settings save(JsonObject request) throws IOException {
    Settings settings = parse(request);
    Files.createDirectories(file.getParent());
    if (Files.isSymbolicLink(file))
      throw new IOException("Preferences file must not be a symbolic link");
    Path temporary = Files.createTempFile(file.getParent(), "preferences-", ".tmp");
    try {
      byte[] content = json.toJson(settings).getBytes(StandardCharsets.UTF_8);
      try (var channel =
          FileChannel.open(
              temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        var buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
      return settings;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static Settings parse(JsonObject request) {
    if (request == null
        || !request.keySet().containsAll(FIELDS)
        || request.keySet().stream()
            .anyMatch(field -> !FIELDS.contains(field) && !READING_FIELDS.contains(field)))
      throw new IllegalArgumentException("Invalid preferences fields");
    int version = integer(request, "schemaVersion");
    String theme = text(request, "theme");
    String mode = text(request, "mode");
    int width = integer(request, "inspectorWidth");
    int zoom = integer(request, "zoom");
    String language = request.has("readingLanguage") ? text(request, "readingLanguage") : "letters";
    String scope = request.has("readingScope") ? text(request, "readingScope") : "all";
    if (version != 1
        || !Set.of("light", "dark").contains(theme)
        || !Set.of("custom", "play", "edit", "study").contains(mode)
        || width < 280
        || width > 460
        || zoom < 50
        || zoom > 200
        || !Set.of("letters", "portuguese").contains(language)
        || !Set.of("all", "selection").contains(scope))
      throw new IllegalArgumentException("Unsupported preferences values");
    return new Settings(
        version,
        theme,
        bool(request, "explorer"),
        bool(request, "inspector"),
        width,
        zoom,
        mode,
        bool(request, "scoreDark"),
        request.has("readingEnabled") && bool(request, "readingEnabled"),
        language,
        request.has("readingOctave") && bool(request, "readingOctave"),
        scope,
        request.has("followFingering") && bool(request, "followFingering"));
  }

  private static int integer(JsonObject request, String field) {
    var value = request.get(field);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Expected integer preference");
    return value.getAsBigDecimal().intValueExact();
  }

  private static String text(JsonObject request, String field) {
    var value = request.get(field);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected text preference");
    return value.getAsString();
  }

  private static boolean bool(JsonObject request, String field) {
    var value = request.get(field);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException("Expected boolean preference");
    return value.getAsBoolean();
  }
}
