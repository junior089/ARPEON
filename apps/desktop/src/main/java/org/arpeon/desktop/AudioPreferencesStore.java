package org.arpeon.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
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

/** Device choice and tuning reference only; never restores permission or capture. */
final class AudioPreferencesStore {
  record Settings(int schemaVersion, String deviceId, String deviceName, double referenceHz) {}

  private final Path file;
  private final Gson json = new GsonBuilder().setStrictness(Strictness.STRICT).create();

  AudioPreferencesStore(Path file) {
    this.file = file.toAbsolutePath().normalize();
  }

  static AudioPreferencesStore defaultStore() {
    return new AudioPreferencesStore(UserDirectories.data().resolve("audio-preferences.json"));
  }

  synchronized Settings load() throws IOException {
    if (!Files.exists(file)) return null;
    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 4096)
      throw new IOException("Invalid audio preferences file");
    try {
      return parse(json.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class));
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid audio preferences", invalid);
    }
  }

  synchronized Settings save(JsonObject request) throws IOException {
    var settings = parse(request);
    Files.createDirectories(file.getParent());
    if (Files.isSymbolicLink(file))
      throw new IOException("Audio preferences must not be a symbolic link");
    Path temporary = Files.createTempFile(file.getParent(), "audio-preferences-", ".tmp");
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
        || !request
            .keySet()
            .equals(Set.of("schemaVersion", "deviceId", "deviceName", "referenceHz")))
      throw new IllegalArgumentException("Invalid audio preferences fields");
    var version = request.get("schemaVersion");
    var reference = request.get("referenceHz");
    if (!version.isJsonPrimitive()
        || !version.getAsJsonPrimitive().isNumber()
        || version.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0
        || !reference.isJsonPrimitive()
        || !reference.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Invalid audio preferences values");
    String id = text(request, "deviceId", 128);
    String name = text(request, "deviceName", 256);
    double hz = reference.getAsDouble();
    if (!Double.isFinite(hz) || hz < 400 || hz > 480)
      throw new IllegalArgumentException("Reference must be between 400 and 480 Hz");
    return new Settings(1, id, name, hz);
  }

  private static String text(JsonObject request, String field, int limit) {
    var value = request.get(field);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected audio preference text");
    String text = value.getAsString();
    if (text.length() > limit || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid audio preference text");
    return text;
  }
}
