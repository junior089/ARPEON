package org.arpeon.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.arpeon.domain.instrument.InstrumentCatalog;

final class LocalAccountStore {
  static final Set<String> AVATARS = Set.of("amber", "sage", "blue", "rose");

  record Profile(
      String id,
      String name,
      String avatar,
      String instrumentId,
      List<String> secondaryInstrumentIds,
      boolean archived) {
    Profile {
      secondaryInstrumentIds = List.copyOf(secondaryInstrumentIds);
    }
  }

  record Account(
      int schemaVersion,
      String id,
      String name,
      String activeProfileId,
      boolean openLastProfile,
      List<Profile> profiles) {
    Account {
      profiles = List.copyOf(profiles);
    }
  }

  record Instrument(String id, String name) {}

  record View(
      String accountId,
      String name,
      String activeProfileId,
      boolean openLastProfile,
      int maxProfiles,
      boolean selectionRequired,
      List<Profile> profiles,
      List<Instrument> instruments) {}

  private final Path directory;
  private final int maxProfiles;
  private final Gson json = new GsonBuilder().setStrictness(Strictness.STRICT).create();
  private Account account;
  private byte[] savedBytes;

  LocalAccountStore(Path root, int maxProfiles) throws IOException {
    if (maxProfiles < 1 || maxProfiles > 32)
      throw new IllegalArgumentException("Limite de perfis inválido.");
    this.maxProfiles = maxProfiles;
    directory = root.toAbsolutePath().normalize().resolve("identity");
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      var profile =
          new Profile(
              UUID.randomUUID().toString(), "Meu estudo", "amber", "concert", List.of(), false);
      var initial =
          new Account(
              1,
              UUID.randomUUID().toString(),
              "Conta local",
              profile.id(),
              false,
              List.of(profile));
      ProfileMigration.initialize(
          root.toAbsolutePath().normalize(),
          profile.id(),
          json.toJson(initial).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    requireDirectory(directory);
    Path file = directory.resolve("account.json");
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(file)
        || Files.size(file) > 65536)
      throw new IOException(
          "O registro da conta local não é seguro ou está ausente. Os dados foram preservados.");
    try {
      savedBytes = Files.readAllBytes(file);
      validateEncoding(
          json.fromJson(
              new String(savedBytes, java.nio.charset.StandardCharsets.UTF_8),
              com.google.gson.JsonObject.class));
      account =
          json.fromJson(
              new String(savedBytes, java.nio.charset.StandardCharsets.UTF_8), Account.class);
      validate(account);
      for (var profile : account.profiles()) requireDirectory(profileDirectory(profile.id()));
    } catch (RuntimeException invalid) {
      throw new IOException(
          "O registro da conta local está inválido. Restaure uma cópia de segurança; os perfis não"
              + " foram recriados.",
          invalid);
    }
  }

  static int configuredLimit() {
    return Integer.parseInt(System.getProperty("arpeon.accountProfiles.max", "8"));
  }

  synchronized Account snapshot() {
    return account;
  }

  synchronized View view(boolean selectionRequired) {
    return new View(
        account.id(),
        account.name(),
        account.activeProfileId(),
        account.openLastProfile(),
        maxProfiles,
        selectionRequired,
        account.profiles(),
        InstrumentCatalog.profiles().stream()
            .filter(item -> !item.id().startsWith("gm-"))
            .map(item -> new Instrument(item.id(), item.displayName()))
            .toList());
  }

  synchronized Profile profile(String id) {
    return account.profiles().stream()
        .filter(item -> item.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Perfil não encontrado nesta conta."));
  }

  synchronized Profile create(
      String name, String avatar, String instrumentId, List<String> secondary) throws IOException {
    if (account.profiles().size() >= maxProfiles)
      throw new IllegalStateException("O limite de perfis desta conta foi atingido.");
    var profile =
        checkedProfile(UUID.randomUUID().toString(), name, avatar, instrumentId, secondary, false);
    Path created = profileDirectory(profile.id());
    Files.createDirectory(created);
    var profiles = new ArrayList<>(account.profiles());
    profiles.add(profile);
    try {
      save(
          new Account(
              1,
              account.id(),
              account.name(),
              account.activeProfileId(),
              account.openLastProfile(),
              List.copyOf(profiles)));
    } catch (IOException | RuntimeException failure) {
      try {
        Files.delete(created);
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
    return profile;
  }

  synchronized void edit(
      String id, String name, String avatar, String instrumentId, List<String> secondary)
      throws IOException {
    var previous = profile(id);
    var updated = checkedProfile(id, name, avatar, instrumentId, secondary, previous.archived());
    save(
        new Account(
            1,
            account.id(),
            account.name(),
            account.activeProfileId(),
            account.openLastProfile(),
            account.profiles().stream()
                .map(item -> item.id().equals(id) ? updated : item)
                .toList()));
  }

  synchronized void archive(String id, boolean archived) throws IOException {
    var previous = profile(id);
    if (archived && account.activeProfileId().equals(id))
      throw new IllegalStateException("Selecione outro perfil antes de arquivar este.");
    var updated =
        new Profile(
            previous.id(),
            previous.name(),
            previous.avatar(),
            previous.instrumentId(),
            previous.secondaryInstrumentIds(),
            archived);
    save(
        new Account(
            1,
            account.id(),
            account.name(),
            account.activeProfileId(),
            account.openLastProfile(),
            account.profiles().stream()
                .map(item -> item.id().equals(id) ? updated : item)
                .toList()));
  }

  synchronized void select(String id) throws IOException {
    if (profile(id).archived())
      throw new IllegalStateException("Restaure o perfil antes de selecioná-lo.");
    requireDirectory(profileDirectory(id));
    save(
        new Account(
            1, account.id(), account.name(), id, account.openLastProfile(), account.profiles()));
  }

  synchronized void preferences(String name, boolean openLastProfile) throws IOException {
    save(
        new Account(
            1,
            account.id(),
            name(name),
            account.activeProfileId(),
            openLastProfile,
            account.profiles()));
  }

  Path profileDirectory(String id) throws IOException {
    if (!isId(id)) throw new IllegalArgumentException("Identificador de perfil inválido.");
    requireDirectory(directory);
    requireDirectory(directory.resolve("profiles"));
    return directory.resolve("profiles").resolve(id);
  }

  private void save(Account next) throws IOException {
    validate(next);
    requireDirectory(directory);
    Path file = directory.resolve("account.json");
    if (Files.isSymbolicLink(file)
        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.size(file) > 65536) throw new IOException("Registro de conta inseguro.");
    byte[] current = Files.readAllBytes(file);
    if (!java.security.MessageDigest.isEqual(current, savedBytes))
      throw new IOException(
          "O registro da conta foi alterado fora desta sessão. Reinicie o aplicativo.");
    Path backup = directory.resolve("account.json.backup");
    writeFile(backup, current);
    byte[] nextBytes = json.toJson(next).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    writeFile(file, nextBytes);
    savedBytes = nextBytes;
    account = next;
  }

  static void writeFile(Path file, byte[] content) throws IOException {
    if (Files.isSymbolicLink(file)) throw new IOException("Arquivo de conta inseguro.");
    Path temporary = Files.createTempFile(file.getParent(), ".account-", ".tmp");
    try {
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
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
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void validate(Account value) {
    if (value == null
        || value.schemaVersion() != 1
        || !isId(value.id())
        || !name(value.name()).equals(value.name())
        || value.profiles() == null
        || value.profiles().isEmpty()
        || value.profiles().size() > 32) throw new IllegalArgumentException("Conta inválida.");
    var ids = new HashSet<String>();
    for (var profile : value.profiles()) {
      if (profile == null
          || !ids.add(profile.id())
          || !checkedProfile(
                  profile.id(),
                  profile.name(),
                  profile.avatar(),
                  profile.instrumentId(),
                  profile.secondaryInstrumentIds(),
                  profile.archived())
              .equals(profile)) throw new IllegalArgumentException("Perfil inválido.");
    }
    if (value.profiles().stream()
        .noneMatch(item -> item.id().equals(value.activeProfileId()) && !item.archived()))
      throw new IllegalArgumentException("Perfil ativo inválido.");
  }

  private static void validateEncoding(com.google.gson.JsonObject data) {
    if (data == null
        || !data.keySet()
            .equals(
                Set.of(
                    "schemaVersion",
                    "id",
                    "name",
                    "activeProfileId",
                    "openLastProfile",
                    "profiles"))) throw new IllegalArgumentException("Formato de conta inválido.");
    var schema = data.get("schemaVersion");
    if (!schema.isJsonPrimitive()
        || !schema.getAsJsonPrimitive().isNumber()
        || schema.getAsBigDecimal().intValueExact() != 1)
      throw new IllegalArgumentException("Versão de conta não suportada.");
    requireTypes(data, List.of("id", "name", "activeProfileId"), List.of("openLastProfile"));
    var profiles = data.get("profiles");
    if (!profiles.isJsonArray()
        || profiles.getAsJsonArray().isEmpty()
        || profiles.getAsJsonArray().size() > 32)
      throw new IllegalArgumentException("Lista de perfis inválida.");
    for (var profile : profiles.getAsJsonArray()) {
      if (!profile.isJsonObject()) throw new IllegalArgumentException("Perfil inválido.");
      var item = profile.getAsJsonObject();
      if (!item.keySet()
          .equals(
              Set.of("id", "name", "avatar", "instrumentId", "secondaryInstrumentIds", "archived")))
        throw new IllegalArgumentException("Formato de perfil inválido.");
      requireTypes(item, List.of("id", "name", "avatar", "instrumentId"), List.of("archived"));
      var instruments = item.get("secondaryInstrumentIds");
      if (!instruments.isJsonArray() || instruments.getAsJsonArray().size() > 16)
        throw new IllegalArgumentException("Instrumentos inválidos.");
      for (var instrument : instruments.getAsJsonArray())
        if (!instrument.isJsonPrimitive() || !instrument.getAsJsonPrimitive().isString())
          throw new IllegalArgumentException("Instrumento inválido.");
    }
  }

  private static void requireTypes(
      com.google.gson.JsonObject data, List<String> texts, List<String> booleans) {
    for (String key : texts)
      if (!data.get(key).isJsonPrimitive() || !data.get(key).getAsJsonPrimitive().isString())
        throw new IllegalArgumentException("Texto de conta inválido.");
    for (String key : booleans)
      if (!data.get(key).isJsonPrimitive() || !data.get(key).getAsJsonPrimitive().isBoolean())
        throw new IllegalArgumentException("Preferência de conta inválida.");
  }

  private static Profile checkedProfile(
      String id,
      String name,
      String avatar,
      String instrumentId,
      List<String> secondary,
      boolean archived) {
    if (!isId(id)
        || !AVATARS.contains(avatar)
        || secondary == null
        || secondary.size() > 16
        || new HashSet<>(secondary).size() != secondary.size()
        || secondary.contains(instrumentId))
      throw new IllegalArgumentException("Dados do perfil inválidos.");
    var supported =
        InstrumentCatalog.profiles().stream()
            .filter(item -> !item.id().startsWith("gm-"))
            .map(item -> item.id())
            .collect(java.util.stream.Collectors.toSet());
    if (!supported.contains(instrumentId) || !supported.containsAll(secondary))
      throw new IllegalArgumentException("Instrumento não reconhecido.");
    return new Profile(id, name(name), avatar, instrumentId, List.copyOf(secondary), archived);
  }

  private static String name(String value) {
    if (value == null) throw new IllegalArgumentException("Informe um nome.");
    String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    if (normalized.isEmpty()
        || normalized.codePointCount(0, normalized.length()) > 48
        || normalized
            .codePoints()
            .anyMatch(
                code ->
                    Character.isISOControl(code) || Character.getType(code) == Character.FORMAT))
      throw new IllegalArgumentException(
          "Use um nome de 1 a 48 caracteres, sem caracteres de controle.");
    return normalized;
  }

  private static boolean isId(String id) {
    if (id == null) return false;
    try {
      return UUID.fromString(id).toString().equals(id);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  static void requireDirectory(Path directory) throws IOException {
    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Diretório de perfil inseguro ou ausente.");
  }
}
