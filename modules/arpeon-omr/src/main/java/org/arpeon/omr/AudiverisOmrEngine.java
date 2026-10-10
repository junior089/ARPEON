package org.arpeon.omr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.arpeon.musicxml.MusicXmlImporter;

/** Isolated Audiveris CLI boundary; no Audiveris classes enter the musical domain. */
public final class AudiverisOmrEngine implements OmrEngine {
  @FunctionalInterface
  interface Runner {
    void run(Path executable, Path source, Path output) throws IOException;
  }

  private static final Duration TIMEOUT = Duration.ofMinutes(10);
  private final Path executable;
  private final boolean fixedExecutable;
  private final Runner runner;
  private final MusicXmlImporter importer = new MusicXmlImporter();

  public AudiverisOmrEngine() {
    String configured = System.getenv("ARPEON_AUDIVERIS_EXECUTABLE");
    executable =
        configured == null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath();
    fixedExecutable = false;
    runner = AudiverisOmrEngine::runCli;
  }

  AudiverisOmrEngine(Path executable, Runner runner) {
    this.executable = executable;
    fixedExecutable = true;
    this.runner = runner;
  }

  public boolean available() {
    Path candidate = resolveExecutable();
    if (candidate == null || !Files.isRegularFile(candidate)) return false;
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    return windows
        ? candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")
        : Files.isExecutable(candidate);
  }

  @Override
  public boolean installable() {
    return !available() && AudiverisInstaller.supported();
  }

  @Override
  public void install() throws IOException {
    if (!installable()) {
      if (available()) return;
      throw new IOException("Automatic Audiveris setup requires 64-bit Windows");
    }
    AudiverisInstaller.install();
  }

  @Override
  public InstallProgress installProgress() {
    return AudiverisInstaller.progress();
  }

  @Override
  public OmrResult recognize(OmrInput input) throws IOException {
    if (!available())
      throw new IOException(
          "Audiveris is not configured; set ARPEON_AUDIVERIS_EXECUTABLE to the installed"
              + " executable");
    int pages = OmrInputValidator.validate(input);
    Path job = Files.createTempDirectory("arpeon-omr-");
    try {
      String extension =
          input.filename().substring(input.filename().lastIndexOf('.')).toLowerCase(Locale.ROOT);
      Path source = job.resolve("source" + extension);
      Path output = Files.createDirectory(job.resolve("output"));
      Files.write(source, input.bytes());
      runner.run(resolveExecutable(), source, output);
      List<Path> exports;
      try (var files = Files.walk(output, 3)) {
        exports =
            files
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(
                    path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                .toList();
      }
      if (exports.size() != 1)
        throw new IOException(
            "Audiveris must produce exactly one MusicXML score; split movements in Audiveris"
                + " first");
      Path xml = exports.getFirst();
      if (Files.size(xml) > MusicXmlImporter.MAX_BYTES)
        throw new IOException("Audiveris MusicXML exceeds 128 MiB");
      var normalized = OmrMusicXml.normalize(Files.readAllBytes(xml));
      var imported =
          importer.read(new ByteArrayInputStream(normalized.xml()), "recognized.musicxml");
      var warnings = new ArrayList<>(normalized.warnings());
      warnings.add(
          "Reconhecimento óptico exige conferência com a imagem original; confiança por nota não"
              + " está disponível.");
      warnings.add("Todas as páginas e medidas requerem revisão antes de usar ou exportar.");
      var reviewPages = new ArrayList<Integer>();
      for (int page = 1; page <= pages; page++) reviewPages.add(page);
      return new OmrResult(
          imported,
          sha256(input.bytes()),
          input.filename(),
          pages,
          warnings,
          reviewPages,
          normalized.unsupportedSymbols());
    } finally {
      deleteJob(job);
    }
  }

  private Path resolveExecutable() {
    if (fixedExecutable) return executable;
    if (executable != null && Files.isRegularFile(executable)) return executable;
    Path local = AudiverisInstaller.installedExecutable();
    if (local != null) return local;
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
      for (String folder : List.of("Program Files", "Program Files (x86)")) {
        Path system =
            Path.of(
                System.getenv().getOrDefault("SystemDrive", "C:\\"),
                folder,
                "Audiveris",
                "Audiveris.exe");
        if (Files.isRegularFile(system)) return system;
        Path bin =
            Path.of(
                System.getenv().getOrDefault("SystemDrive", "C:\\"),
                folder,
                "Audiveris",
                "bin",
                "Audiveris.exe");
        if (Files.isRegularFile(bin)) return bin;
      }
      String localAppData = System.getenv("LOCALAPPDATA");
      if (localAppData != null) {
        Path perUser = Path.of(localAppData, "Programs", "Audiveris", "Audiveris.exe");
        if (Files.isRegularFile(perUser)) return perUser;
      }
    }
    return executable;
  }

  private static void runCli(Path executable, Path source, Path output) throws IOException {
    List<String> command =
        List.of(
            executable.toString(),
            "-batch",
            "-export",
            "-constant",
            "org.audiveris.omr.sheet.BookManager.useCompression=false",
            "-output",
            output.toString(),
            "--",
            source.toString());
    var process =
        new ProcessBuilder(command)
            .directory(source.getParent().toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    boolean finished;
    try {
      finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException failure) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Audiveris recognition interrupted", failure);
    }
    if (!finished) {
      process.destroyForcibly();
      try {
        process.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      }
      throw new IOException("Audiveris exceeded the ten-minute recognition limit");
    }
    if (process.exitValue() != 0) throw new IOException("Audiveris could not recognize this score");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void deleteJob(Path job) throws IOException {
    try (var paths = Files.walk(job)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }
}
