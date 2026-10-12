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
  public OmrResult recognize(OmrInput input) throws IOException {
    return recognize(input, ignored -> {});
  }

  @Override
  public OmrResult recognize(OmrInput input, java.util.function.Consumer<String> phase)
      throws IOException {
    if (!available())
      throw new IOException(
          "O reconhecimento óptico não está disponível neste pacote. Reinstale o ARPEON com o"
              + " pacote completo.");
    phase.accept("reading");
    int pages = OmrInputValidator.validate(input);
    String configuredTemp = System.getProperty("arpeon.omr.tempDir");
    Path job;
    if (configuredTemp == null) job = Files.createTempDirectory("arpeon-omr-");
    else {
      Path temp = Path.of(configuredTemp).toAbsolutePath().normalize();
      Files.createDirectories(temp);
      if (Files.isSymbolicLink(temp)) throw new IOException("Unsafe OMR temporary directory");
      job = Files.createTempDirectory(temp, "arpeon-omr-");
    }
    try {
      String extension =
          input.filename().substring(input.filename().lastIndexOf('.')).toLowerCase(Locale.ROOT);
      Path source = job.resolve("source" + extension);
      Path output = Files.createDirectory(job.resolve("output"));
      Files.write(source, input.bytes());
      checkInterrupted();
      phase.accept("recognizing");
      runner.run(resolveExecutable(), source, output);
      checkInterrupted();
      phase.accept("preparing");
      List<Path> exports;
      try (var files = Files.walk(output, 3)) {
        exports =
            files
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(
                    path -> {
                      String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                      return name.endsWith(".xml") || name.endsWith(".musicxml");
                    })
                .toList();
      }
      if (exports.isEmpty())
        throw new IOException(
            "O Audiveris terminou sem exportar uma partitura MusicXML. Confira"
                + " se o PDF contém pautas impressas legíveis e tente novamente.");
      if (exports.size() != 1)
        throw new IOException(
            "O Audiveris exportou mais de uma partitura. Separe os movimentos no Audiveris e"
                + " importe um MusicXML de cada vez.");
      Path xml = exports.getFirst();
      if (Files.size(xml) > MusicXmlImporter.MAX_BYTES)
        throw new IOException("Audiveris MusicXML exceeds 128 MiB");
      var normalized = OmrMusicXml.normalize(Files.readAllBytes(xml));
      var imported =
          importer.read(new ByteArrayInputStream(normalized.xml()), "recognized.musicxml");
      checkInterrupted();
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
    if (fixedExecutable || executable != null) return executable;
    String home = System.getProperty("arpeon.audiveris.home");
    if (home == null || home.isBlank()) return null;
    Path root = Path.of(home).toAbsolutePath().normalize();
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    return root.resolve(windows ? "Audiveris.exe" : "bin/Audiveris");
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
    AudiverisProcess.run(command, source.getParent(), TIMEOUT);
  }

  private static void checkInterrupted() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("Reconhecimento cancelado.");
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
