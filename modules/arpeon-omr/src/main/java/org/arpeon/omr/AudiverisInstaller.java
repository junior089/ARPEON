package org.arpeon.omr;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Installs the official Windows Audiveris release into ARPEON's per-user data folder. */
final class AudiverisInstaller {
  static final String VERSION = "5.11.0";
  private static final String SHA256 =
      "ac221b0d39a90e32b7f43dbf9f5d5a45ff8b5424076af21b65278955688dac71";
  private static final long MAX_INSTALLER_BYTES = 120L * 1024 * 1024;
  private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(8);
  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(3);
  private static final URI DOWNLOAD_URI =
      URI.create(
          "https://github.com/Audiveris/audiveris/releases/download/"
              + VERSION
              + "/Audiveris-"
              + VERSION
              + "-windows-x86_64.msi");
  private static volatile OmrEngine.InstallProgress progress =
      new OmrEngine.InstallProgress("idle", 0, 0);
  private static volatile Path cachedExecutable;

  private AudiverisInstaller() {}

  static boolean supported() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    return os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"));
  }

  static Path installedExecutable() {
    Path cached = cachedExecutable;
    if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) return cached;
    Path root = installRoot();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return null;
    try (Stream<Path> paths = Files.walk(root, 7)) {
      Path discovered =
          paths
              .filter(path -> path.getFileName().toString().equalsIgnoreCase("Audiveris.exe"))
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .findFirst()
              .orElse(null);
      cachedExecutable = discovered;
      return discovered;
    } catch (IOException | SecurityException failure) {
      return null;
    }
  }

  static synchronized Path install() throws IOException {
    progress = new OmrEngine.InstallProgress("downloading", 0, 0);
    try {
      if (!supported()) throw new IOException("Automatic Audiveris setup requires 64-bit Windows");
      Path existing = installedExecutable();
      if (existing != null) {
        progress = new OmrEngine.InstallProgress("ready", 0, 0);
        return existing;
      }

      Path parent = installRoot().getParent();
      Files.createDirectories(parent);
      Path target = installRoot();
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        throw new IOException(
            "The local Audiveris folder exists but its executable is missing. Move this folder"
                + " and try again: "
                + target);

      Path stage = parent.resolve(".audiveris-stage-" + UUID.randomUUID());
      Files.createDirectory(stage);
      try {
        Path installer = stage.resolve("audiveris.msi");
        download(installer);
        Path extracted = Files.createDirectory(stage.resolve("files"));
        progress = new OmrEngine.InstallProgress("extracting", 0, 0);
        extract(installer, extracted);
        Path executable = findExecutable(extracted);
        if (executable == null)
          throw new IOException("The official Audiveris package did not contain Audiveris.exe");

        progress = new OmrEngine.InstallProgress("checking", 0, 0);
        verifyExecutable(executable);
        move(extracted, target);
        Path relativeExecutable = extracted.relativize(executable);
        cachedExecutable = target.resolve(relativeExecutable);
        progress = new OmrEngine.InstallProgress("ready", 0, 0);
        return cachedExecutable;
      } finally {
        deleteTree(stage);
      }
    } catch (IOException | RuntimeException failure) {
      progress = new OmrEngine.InstallProgress("failed", 0, 0);
      throw failure;
    }
  }

  static OmrEngine.InstallProgress progress() {
    return progress;
  }

  private static void download(Path destination) throws IOException {
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    var request =
        HttpRequest.newBuilder(DOWNLOAD_URI)
            .timeout(DOWNLOAD_TIMEOUT)
            .header("User-Agent", "ARPEON local Audiveris setup")
            .GET()
            .build();
    HttpResponse<InputStream> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IOException("Audiveris download was interrupted", failure);
    }
    try (InputStream body = response.body()) {
      if (response.statusCode() != 200)
        throw new IOException("Could not download Audiveris from its official release page");
      long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(0);
      progress = new OmrEngine.InstallProgress("downloading", 0, totalBytes);
      try (var output = Files.newOutputStream(destination)) {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = body.read(buffer)) != -1) {
          total += count;
          if (total > MAX_INSTALLER_BYTES)
            throw new IOException("The Audiveris download exceeded the 120 MiB limit");
          output.write(buffer, 0, count);
          progress = new OmrEngine.InstallProgress("downloading", total, totalBytes);
        }
        if (total == 0) throw new IOException("The Audiveris download was empty");
      }
    }
    progress = new OmrEngine.InstallProgress("verifying", 0, 0);
    if (!SHA256.equals(sha256(destination)))
      throw new IOException("The Audiveris download failed its SHA-256 integrity check");
  }

  private static void verifyExecutable(Path executable) throws IOException {
    var process =
        new ProcessBuilder(executable.toString(), "-version")
            .directory(executable.getParent().toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    boolean finished;
    try {
      finished = process.waitFor(60, TimeUnit.SECONDS);
    } catch (InterruptedException failure) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Audiveris verification was interrupted", failure);
    }
    if (!finished) {
      process.destroyForcibly();
      try {
        process.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      }
      throw new IOException("Audiveris did not start within one minute");
    }
    if (process.exitValue() != 0)
      throw new IOException("The downloaded Audiveris executable failed its startup check");
  }

  private static void extract(Path installer, Path destination) throws IOException {
    var process =
        new ProcessBuilder(
                "msiexec.exe",
                "/a",
                installer.toString(),
                "/qn",
                "/norestart",
                "TARGETDIR=" + destination)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    boolean finished;
    try {
      finished = process.waitFor(INSTALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException failure) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Audiveris setup was interrupted", failure);
    }
    if (!finished) {
      process.destroyForcibly();
      try {
        process.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      }
      throw new IOException("Audiveris setup exceeded the three-minute limit");
    }
    if (process.exitValue() != 0)
      throw new IOException(
          "Windows could not extract Audiveris. Check that the ARPEON folder is writable and"
              + " retry.");
  }

  private static Path findExecutable(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root, 7)) {
      return paths
          .filter(path -> path.getFileName().toString().equalsIgnoreCase("Audiveris.exe"))
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .findFirst()
          .orElse(null);
    }
  }

  private static Path installRoot() {
    return Path.of(System.getProperty("user.home"), ".arpeon", "tools", "audiveris-" + VERSION)
        .toAbsolutePath()
        .normalize();
  }

  private static String sha256(Path path) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(path)) {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void move(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(source, target);
    }
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
        Files.deleteIfExists(path);
    }
  }
}
