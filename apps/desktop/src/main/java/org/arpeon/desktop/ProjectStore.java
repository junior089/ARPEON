package org.arpeon.desktop;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Versioned local projects with optional verified originals, committed with the score revision. */
final class ProjectStore {
  static final int MAX_PROJECTS = 500;
  private static final int MAX_MANIFEST_BYTES = 64 * 1024;
  private static final int SCHEMA_VERSION = 5;
  private static final String SCORE_FILE = "score.musicxml";
  private static final String MANIFEST_FILE = "manifest.json";
  private static final String ORIGINAL_FILE = "source.bin";

  @FunctionalInterface
  interface DurableWriter {
    void write(Path path, byte[] content) throws IOException;
  }

  record Data(
      String title,
      String filename,
      String sha256,
      String parserVersion,
      String sourceKind,
      List<String> importWarnings,
      ImportWorkspace.Source source,
      String xml,
      RehearsalState rehearsal,
      ProjectProvenance provenance,
      OriginalScore original,
      List<EditionEvidence> editions) {
    Data {
      editions = List.copyOf(editions);
    }

    Data withEditions(List<EditionEvidence> history) {
      return new Data(
          title,
          filename,
          sha256,
          parserVersion,
          sourceKind,
          importWarnings,
          source,
          xml,
          rehearsal,
          provenance,
          original,
          history);
    }

    Data(
        String title,
        String filename,
        String sha256,
        String parserVersion,
        String sourceKind,
        List<String> importWarnings,
        ImportWorkspace.Source source,
        String xml,
        RehearsalState rehearsal,
        ProjectProvenance provenance,
        OriginalScore original) {
      this(
          title,
          filename,
          sha256,
          parserVersion,
          sourceKind,
          importWarnings,
          source,
          xml,
          rehearsal,
          provenance,
          original,
          List.of());
    }

    Data(
        String title,
        String filename,
        String sha256,
        String parserVersion,
        String sourceKind,
        List<String> importWarnings,
        ImportWorkspace.Source source,
        String xml,
        RehearsalState rehearsal) {
      this(
          title,
          filename,
          sha256,
          parserVersion,
          sourceKind,
          importWarnings,
          source,
          xml,
          rehearsal,
          null,
          null);
    }

    Data(
        String title,
        String filename,
        String sha256,
        String parserVersion,
        String sourceKind,
        List<String> importWarnings,
        ImportWorkspace.Source source,
        String xml) {
      this(title, filename, sha256, parserVersion, sourceKind, importWarnings, source, xml, null);
    }
  }

  record Info(String id, String title, String filename, String updatedAt) {}

  record Saved(String id, String updatedAt) {}

  record Project(Data data, String createdAt, String updatedAt) {}

  record Details(ProjectProvenance provenance, String xmlSha256) {}

  record Editions(
      String sourceAssetId, String workId, List<EditionEvidence> history, int maxEntries) {}

  private record Manifest(
      int schemaVersion,
      String id,
      String title,
      String filename,
      String sha256,
      String parserVersion,
      String sourceKind,
      List<String> importWarnings,
      ImportWorkspace.Source source,
      String xmlSha256,
      String createdAt,
      String updatedAt,
      String name,
      RehearsalState rehearsal,
      ProjectProvenance provenance,
      List<EditionEvidence> editions) {}

  private final Path root;
  private final DurableWriter durableWriter;
  private final Gson json = new Gson();

  ProjectStore(Path root) {
    this(root, ProjectStore::writeAndSync);
  }

  ProjectStore(Path root, DurableWriter durableWriter) {
    this.root = root.toAbsolutePath().normalize();
    this.durableWriter = durableWriter;
  }

  static ProjectStore defaultStore() {
    return new ProjectStore(UserDirectories.data().resolve("projects"));
  }

  synchronized List<Info> list() throws IOException {
    if (!Files.exists(root)) return List.of();
    requireDirectory(root);
    recoverBackups();
    var result = new ArrayList<Info>();
    try (var entries = Files.list(root)) {
      for (Path directory : entries.sorted().toList()) {
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) continue;
        String id = directory.getFileName().toString();
        if (!isId(id)) continue;
        Manifest manifest;
        try {
          manifest = readManifest(directory, id);
        } catch (IOException invalid) {
          System.getLogger(ProjectStore.class.getName())
              .log(System.Logger.Level.WARNING, "Ignoring an invalid local project manifest");
          continue;
        }
        result.add(new Info(id, projectName(manifest), manifest.filename(), manifest.updatedAt()));
        if (result.size() > MAX_PROJECTS)
          throw new IOException("Local project limit reached; remove projects before adding more");
      }
    }
    result.sort((left, right) -> right.updatedAt().compareTo(left.updatedAt()));
    return List.copyOf(result);
  }

  synchronized Saved save(String requestedId, Data data) throws IOException {
    return save(requestedId, data, null);
  }

  synchronized boolean contains(String id) throws IOException {
    if (!isId(id) || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
    requireDirectory(root);
    recoverBackups();
    Path directory = root.resolve(id);
    return !Files.isSymbolicLink(directory)
        && Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS);
  }

  synchronized Saved rename(String id, String name) throws IOException {
    return save(id, load(id).data(), validName(name));
  }

  synchronized Saved duplicate(String id, String name) throws IOException {
    return save(null, load(id).data(), validName(name));
  }

  private Saved save(String requestedId, Data data, String name) throws IOException {
    return save(requestedId, data, name, null);
  }

  private Saved save(
      String requestedId, Data data, String name, List<EditionEvidence> editionOverride)
      throws IOException {
    String id =
        requestedId == null || requestedId.isBlank() ? UUID.randomUUID().toString() : requestedId;
    if (!isId(id)) throw new IllegalArgumentException("Invalid project ID");
    validate(data);
    Files.createDirectories(root);
    requireDirectory(root);
    recoverBackups();
    Path target = root.resolve(id);
    if (!Files.exists(target) && list().size() >= MAX_PROJECTS)
      throw new IOException("Local project limit reached; remove projects before adding more");

    Manifest previous = Files.exists(target) ? readManifest(target, id) : null;
    if (previous != null
        && previous.provenance() != null
        && !previous.provenance().equals(data.provenance()))
      throw new IOException(
          "A origem inicial é imutável. Salve a nova entrada como outro projeto.");
    String createdAt = previous == null ? java.time.Instant.now().toString() : previous.createdAt();
    String updatedAt = java.time.Instant.now().toString();
    List<EditionEvidence> editions =
        editionOverride != null
            ? editionOverride
            : previous == null ? data.editions() : history(previous);
    EditionEvidence.validate(editions, data.provenance());
    byte[] xml = data.xml().getBytes(StandardCharsets.UTF_8);
    Manifest manifest =
        new Manifest(
            SCHEMA_VERSION,
            id,
            data.title(),
            data.filename(),
            data.sha256(),
            data.parserVersion(),
            data.sourceKind(),
            List.copyOf(data.importWarnings()),
            data.source(),
            sha256(xml),
            createdAt,
            updatedAt,
            name != null ? name : previous == null ? null : previous.name(),
            data.rehearsal(),
            data.provenance(),
            editions);

    Path staging = Files.createTempDirectory(root, ".pending-");
    Path backup = root.resolve(".backup-" + id + "-" + UUID.randomUUID());
    boolean backedUp = false;
    try {
      durableWriter.write(staging.resolve(SCORE_FILE), xml);
      if (data.original() != null)
        data.original().write(staging.resolve(ORIGINAL_FILE), durableWriter);
      byte[] encodedManifest = json.toJson(manifest).getBytes(StandardCharsets.UTF_8);
      if (encodedManifest.length > MAX_MANIFEST_BYTES)
        throw new IOException("Project manifest exceeds the supported limit");
      durableWriter.write(staging.resolve(MANIFEST_FILE), encodedManifest);
      if (Files.exists(target)) {
        move(target, backup);
        backedUp = true;
      }
      try {
        move(staging, target);
      } catch (IOException failure) {
        if (backedUp) move(backup, target);
        throw failure;
      }
      if (backedUp) {
        try {
          deleteProjectDirectory(backup);
        } catch (IOException cleanupFailure) {
          System.getLogger(ProjectStore.class.getName())
              .log(System.Logger.Level.WARNING, "Saved project backup could not be removed");
        }
      }
      return new Saved(id, updatedAt);
    } finally {
      if (Files.exists(staging)) deleteProjectDirectory(staging);
    }
  }

  synchronized Project load(String id) throws IOException {
    if (!isId(id)) throw new IllegalArgumentException("Invalid project ID");
    if (Files.isDirectory(root) && !Files.isSymbolicLink(root)) recoverBackups();
    Path directory = root.resolve(id);
    requireDirectory(directory);
    Manifest manifest = readManifest(directory, id);
    verifyStoredScore(directory, manifest);
    Path scorePath = directory.resolve(SCORE_FILE);
    byte[] bytes = Files.readAllBytes(scorePath);
    String xml =
        StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    return new Project(
        new Data(
            manifest.title(),
            manifest.filename(),
            manifest.sha256(),
            manifest.parserVersion(),
            manifest.sourceKind(),
            manifest.importWarnings(),
            manifest.source(),
            xml,
            manifest.rehearsal(),
            manifest.provenance(),
            readOriginal(directory, manifest),
            history(manifest)),
        manifest.createdAt(),
        manifest.updatedAt());
  }

  synchronized void delete(String id) throws IOException {
    if (!isId(id)) throw new IllegalArgumentException("Invalid project ID");
    if (Files.isDirectory(root) && !Files.isSymbolicLink(root)) recoverBackups();
    Path directory = root.resolve(id);
    readManifest(directory, id);
    deleteProjectDirectory(directory);
  }

  synchronized Details details(String id) throws IOException {
    if (!isId(id)) throw new IllegalArgumentException("Invalid project ID");
    if (Files.isDirectory(root) && !Files.isSymbolicLink(root)) recoverBackups();
    Path directory = root.resolve(id);
    var manifest = readManifest(directory, id);
    verifyStoredScore(directory, manifest);
    readOriginal(directory, manifest);
    return new Details(manifest.provenance(), manifest.xmlSha256());
  }

  synchronized OriginalScore original(String id) throws IOException {
    if (!isId(id)) throw new IllegalArgumentException("Invalid project ID");
    if (Files.isDirectory(root) && !Files.isSymbolicLink(root)) recoverBackups();
    Path directory = root.resolve(id);
    return readOriginal(directory, readManifest(directory, id));
  }

  private static OriginalScore readOriginal(Path directory, Manifest manifest) throws IOException {
    var provenance = manifest.provenance();
    if (provenance == null || !provenance.sourceAsset().retained()) return null;
    Path file = directory.resolve(ORIGINAL_FILE);
    if (Files.isSymbolicLink(file)
        || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        || Files.size(file) != provenance.sourceAsset().byteSize())
      throw new IOException("Original ausente ou inválido.");
    var original = new OriginalScore(manifest.filename(), Files.readAllBytes(file));
    if (!original.sha256().equals(manifest.sha256())
        || !original.format().equals(provenance.sourceAsset().format()))
      throw new IOException("A verificação de integridade do original falhou.");
    return original;
  }

  synchronized Editions editions(String id) throws IOException {
    var data = load(id).data();
    var source = data.provenance();
    return new Editions(
        source == null ? null : source.sourceAsset().id(),
        source == null ? null : source.work().id(),
        data.editions(),
        EditionEvidence.MAX_ENTRIES);
  }

  synchronized Editions identifyEdition(
      String id,
      String expectedAsset,
      int expectedRevision,
      String title,
      String citation,
      String referenceUrl)
      throws IOException {
    var data = load(id).data();
    if (data.provenance() == null || !data.provenance().sourceAsset().id().equals(expectedAsset))
      throw new IllegalStateException(
          "A origem do projeto não corresponde à identificação. Consulte o projeto novamente.");
    if (data.editions().size() != expectedRevision)
      throw new IllegalStateException(
          "A edição foi alterada por outra operação. Consulte o histórico antes de tentar"
              + " novamente.");
    if (expectedRevision >= EditionEvidence.MAX_ENTRIES)
      throw new IllegalStateException(
          "O limite de 16 registros de identificação deste projeto foi atingido.");
    var entry = EditionEvidence.record(data.provenance(), title, citation, referenceUrl);
    if (entry.edition() == null
        && (data.editions().isEmpty() || data.editions().getLast().edition() == null))
      throw new IllegalStateException("Não há identificação ativa para retirar.");
    var entries = new ArrayList<>(data.editions());
    entries.add(entry);
    save(id, data, null, List.copyOf(entries));
    return editions(id);
  }

  private static List<EditionEvidence> history(Manifest manifest) {
    return manifest.editions() == null ? List.of() : manifest.editions();
  }

  private Manifest readManifest(Path directory, String expectedId) throws IOException {
    requireDirectory(directory);
    Path path = directory.resolve(MANIFEST_FILE);
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path))
      throw new IOException("Project manifest is missing");
    if (Files.size(path) > MAX_MANIFEST_BYTES)
      throw new IOException("Project manifest is too large");
    try {
      Manifest manifest =
          json.fromJson(Files.readString(path, StandardCharsets.UTF_8), Manifest.class);
      if (manifest == null
          || (manifest.schemaVersion() < 1 || manifest.schemaVersion() > SCHEMA_VERSION)
          || !expectedId.equals(manifest.id())
          || manifest.title() == null
          || manifest.filename() == null
          || manifest.sha256() == null
          || manifest.parserVersion() == null
          || manifest.sourceKind() == null
          || manifest.importWarnings() == null
          || manifest.xmlSha256() == null
          || manifest.createdAt() == null
          || manifest.updatedAt() == null
          || !manifest.xmlSha256().matches("[0-9a-f]{64}"))
        throw new IOException("Invalid project manifest");
      if (manifest.name() != null) {
        try {
          validName(manifest.name());
        } catch (IllegalArgumentException invalidName) {
          throw new IOException("Invalid project name", invalidName);
        }
      }
      validate(
          new Data(
              manifest.title(),
              manifest.filename(),
              manifest.sha256(),
              manifest.parserVersion(),
              manifest.sourceKind(),
              manifest.importWarnings(),
              manifest.source(),
              "<x/>"));
      if (manifest.provenance() != null)
        manifest.provenance().validate(manifest.filename(), manifest.sha256());
      if (manifest.schemaVersion() >= 4 && manifest.editions() == null)
        throw new IOException("Histórico da edição ausente.");
      EditionEvidence.validate(history(manifest), manifest.provenance());
      if ((manifest.provenance() == null || !manifest.provenance().sourceAsset().retained())
          && Files.exists(
              directory.resolve(ORIGINAL_FILE), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Original presente sem registro de proveniência correspondente.");
      try {
        java.time.Instant.parse(manifest.createdAt());
        java.time.Instant.parse(manifest.updatedAt());
      } catch (java.time.format.DateTimeParseException invalidTimestamp) {
        throw new IOException("Invalid project timestamps", invalidTimestamp);
      }
      return manifest;
    } catch (RuntimeException failure) {
      throw new IOException("Invalid project manifest", failure);
    }
  }

  private void recoverBackups() throws IOException {
    try (var entries = Files.list(root)) {
      for (Path backup : entries.toList()) {
        String name = backup.getFileName().toString();
        if (!name.startsWith(".backup-") || name.length() != 8 + 36 + 1 + 36) continue;
        String id = name.substring(8, 44);
        if (name.charAt(44) != '-' || !isId(id) || Files.exists(root.resolve(id))) continue;
        try {
          Manifest manifest = readManifest(backup, id);
          verifyStoredScore(backup, manifest);
          readOriginal(backup, manifest);
          move(backup, root.resolve(id));
        } catch (IOException invalidBackup) {
          System.getLogger(ProjectStore.class.getName())
              .log(System.Logger.Level.WARNING, "A local project backup could not be restored");
        }
      }
    }
  }

  private static void writeAndSync(Path path, byte[] content) throws IOException {
    Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private static void verifyStoredScore(Path directory, Manifest manifest) throws IOException {
    Path scorePath = directory.resolve(SCORE_FILE);
    if (Files.isSymbolicLink(scorePath) || !Files.isRegularFile(scorePath))
      throw new IOException("Project MusicXML is missing or invalid");
    long size = Files.size(scorePath);
    if (size < 1 || size > org.arpeon.musicxml.MusicXmlImporter.MAX_BYTES)
      throw new IOException("Project MusicXML size is outside the supported limit");
    byte[] bytes = Files.readAllBytes(scorePath);
    if (!MessageDigest.isEqual(
        manifest.xmlSha256().getBytes(StandardCharsets.US_ASCII),
        sha256(bytes).getBytes(StandardCharsets.US_ASCII)))
      throw new IOException("Project integrity check failed");
  }

  private static void validate(Data data) throws IOException {
    if (data == null
        || data.title() == null
        || data.title().length() > 500
        || data.filename() == null
        || data.filename().isBlank()
        || data.filename().length() > 200
        || data.filename().matches(".*[/\\\\:].*")
        || data.sha256() == null
        || !data.sha256().matches("[0-9a-f]{64}")
        || data.parserVersion() == null
        || data.parserVersion().length() > 100
        || data.sourceKind() == null
        || data.sourceKind().length() > 40
        || data.importWarnings() == null
        || data.importWarnings().size() > 256
        || data.importWarnings().stream().anyMatch(w -> w == null || w.length() > 2000)
        || data.xml() == null
        || data.xml().isBlank()
        || data.xml().getBytes(StandardCharsets.UTF_8).length
            > org.arpeon.musicxml.MusicXmlImporter.MAX_BYTES)
      throw new IOException("Project data is invalid or exceeds supported limits");
    if (data.source() != null
        && (!("wikimedia-commons".equals(data.source().providerId())
                    && safeCommonsUrl(data.source().pageUrl())
                || "mutopia".equals(data.source().providerId())
                    && MutopiaCatalogue.PAGE.equals(data.source().pageUrl()))
            || (data.source().licenseUrl() != null && !safeHttpsUrl(data.source().licenseUrl()))
            || !validSourceMetadata(data.source().metadata())
            || data.source().notes() == null
            || data.source().notes().size() > 64
            || data.source().notes().stream()
                .anyMatch(note -> note == null || note.length() > 1000)))
      throw new IOException("Project source metadata is invalid");
    if (data.provenance() != null) {
      data.provenance().validate(data.filename(), data.sha256());
      if (data.provenance().sourceAsset().retained() != (data.original() != null))
        throw new IOException("O original não corresponde à proveniência.");
    } else if (data.original() != null) throw new IOException("Original sem proveniência.");
    if (data.original() != null
        && (!data.original().filename().equals(data.filename())
            || !data.original().sha256().equals(data.sha256())
            || data.original().size() != data.provenance().sourceAsset().byteSize()
            || !data.original().format().equals(data.provenance().sourceAsset().format())))
      throw new IOException("O original não corresponde à proveniência.");
    EditionEvidence.validate(data.editions(), data.provenance());
  }

  private static boolean safeCommonsUrl(String value) {
    return safeHttpsUrl(value)
        && "commons.wikimedia.org".equalsIgnoreCase(java.net.URI.create(value).getHost());
  }

  private static boolean validSourceMetadata(ImportWorkspace.SourceMetadata metadata) {
    return metadata == null
        || (metadata.title() != null
            && !metadata.title().isBlank()
            && metadata.title().length() <= 500
            && metadata.composer() != null
            && metadata.composer().length() <= 500
            && metadata.instrumentId() != null
            && metadata.instrumentId().equals("flute")
            && (metadata.title() + metadata.composer())
                .codePoints()
                .noneMatch(
                    code ->
                        Character.isISOControl(code)
                            || Character.getType(code) == Character.FORMAT));
  }

  private static boolean safeHttpsUrl(String value) {
    try {
      var uri = java.net.URI.create(value);
      return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static boolean isId(String id) {
    try {
      return UUID.fromString(id).toString().equals(id);
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static String projectName(Manifest manifest) {
    return manifest.name() == null ? manifest.title() : manifest.name();
  }

  private static String validName(String name) {
    if (name == null
        || name.isBlank()
        || name.strip().length() > 200
        || name.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(
          "Use um nome de projeto entre 1 e 200 caracteres, sem quebras de linha.");
    return name.strip();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void move(Path source, Path destination) throws IOException {
    for (int attempt = 0; ; attempt++) {
      try {
        try {
          Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
          Files.move(source, destination);
        }
        return;
      } catch (java.nio.file.AccessDeniedException locked) {
        if (!System.getProperty("os.name").startsWith("Windows") || attempt >= 3) throw locked;
        // Windows scanners can briefly keep a just-written score directory open.
        System.getLogger(ProjectStore.class.getName())
            .log(System.Logger.Level.DEBUG, "Retrying locked project directory move");
        try {
          Thread.sleep(50L * (attempt + 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          var failure =
              new java.io.InterruptedIOException(
                  "Project save interrupted while waiting for the directory");
          failure.initCause(interrupted);
          throw failure;
        }
      }
    }
  }

  private static void requireDirectory(Path directory) throws IOException {
    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory))
      throw new IOException("Local project directory is missing or unsafe");
  }

  private static void deleteProjectDirectory(Path directory) throws IOException {
    if (Files.isSymbolicLink(directory)) throw new IOException("Refusing project symlink");
    try (var files = Files.list(directory)) {
      for (Path file : files.toList()) {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file))
          throw new IOException("Unexpected file in project directory");
        Files.delete(file);
      }
    }
    Files.delete(directory);
  }
}
