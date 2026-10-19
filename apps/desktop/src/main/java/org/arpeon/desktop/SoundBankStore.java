package org.arpeon.desktop;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Soundbank;

/** User-supplied sound banks are local assets, addressed and verified by their content hash. */
final class SoundBankStore {
  static final int MAX_BYTES = 128 * 1024 * 1024;
  private static final int MAX_BANKS = 8;

  record Info(String id, String name, String filename, long bytes, int programs) {}

  record Loaded(Info info, Soundbank bank) {}

  private final Path root;
  private final Gson json = new Gson();

  SoundBankStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  synchronized List<Info> list() throws IOException {
    if (!Files.exists(root)) return List.of();
    safeRoot();
    var result = new ArrayList<Info>();
    try (var files = Files.list(root)) {
      for (Path path :
          files
              .filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.json"))
              .sorted()
              .toList()) {
        if (Files.isSymbolicLink(path) || Files.size(path) > 2048)
          throw new IOException("Metadados de banco inválidos.");
        Info info;
        try {
          info = json.fromJson(Files.readString(path), Info.class);
        } catch (com.google.gson.JsonParseException failure) {
          throw new IOException("Metadados de banco inválidos.", failure);
        }
        if (info == null
            || !path.getFileName().toString().equals(info.id() + ".json")
            || info.name() == null
            || info.filename() == null
            || info.bytes() < 1
            || info.bytes() > MAX_BYTES
            || info.programs() < 1
            || info.programs() > 128) throw new IOException("Metadados de banco inválidos.");
        result.add(info);
        if (result.size() > MAX_BANKS) throw new IOException("Limite de oito bancos de sons.");
      }
    }
    return List.copyOf(result);
  }

  synchronized Info importBank(byte[] bytes, String filename) throws IOException {
    if (bytes.length == 0
        || bytes.length > MAX_BYTES
        || filename == null
        || filename.length() > 200
        || !filename.toLowerCase(java.util.Locale.ROOT).matches("[^/\\\\]+\\.(sf2|dls)")
        || filename.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Escolha um banco SF2 ou DLS de até 128 MiB.");
    String id = hash(bytes);
    var existing = list();
    if (existing.size() >= MAX_BANKS && existing.stream().noneMatch(info -> info.id().equals(id)))
      throw new IllegalArgumentException("Limite de oito bancos de sons.");
    Soundbank bank = decode(bytes);
    int programs =
        (int)
            java.util.Arrays.stream(bank.getInstruments())
                .filter(i -> i.getPatch().getBank() == 0)
                .map(i -> i.getPatch().getProgram())
                .distinct()
                .count();
    if (programs == 0)
      throw new IllegalArgumentException("O banco não contém programas MIDI no banco 0.");
    String name = bank.getName() == null || bank.getName().isBlank() ? filename : bank.getName();
    name =
        name.codePoints()
            .filter(c -> !Character.isISOControl(c))
            .limit(120)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    var info = new Info(id, name, filename, bytes.length, programs);
    Files.createDirectories(root);
    safeRoot();
    DurableFiles.replace(root.resolve(id + ".bank"), bytes);
    DurableFiles.replace(
        root.resolve(id + ".json"), json.toJson(info).getBytes(StandardCharsets.UTF_8));
    return info;
  }

  synchronized Loaded load(String id) throws IOException {
    if (id == null || !id.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Banco inválido.");
    var info =
        list().stream()
            .filter(i -> i.id().equals(id))
            .findFirst()
            .orElseThrow(
                () ->
                    new IOException(
                        "Banco ausente neste computador. Importe o mesmo arquivo ou escolha outro"
                            + " banco."));
    Path path = root.resolve(id + ".bank");
    if (Files.isSymbolicLink(path)
        || !Files.isRegularFile(path)
        || Files.size(path) != info.bytes())
      throw new IOException("Arquivo do banco ausente ou alterado.");
    byte[] bytes = Files.readAllBytes(path);
    if (!hash(bytes).equals(id))
      throw new IOException("A integridade do banco de sons não confere.");
    return new Loaded(info, decode(bytes));
  }

  synchronized void delete(String id) throws IOException {
    if (id == null || !id.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Banco inválido.");
    if (list().stream().noneMatch(i -> i.id().equals(id)))
      throw new IllegalArgumentException("Banco não encontrado.");
    Files.deleteIfExists(root.resolve(id + ".bank"));
    Files.deleteIfExists(root.resolve(id + ".json"));
  }

  private void safeRoot() throws IOException {
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root))
      throw new IOException("Pasta de bancos de sons inválida.");
  }

  private static Soundbank decode(byte[] bytes) throws IOException {
    try {
      Soundbank bank = MidiSystem.getSoundbank(new ByteArrayInputStream(bytes));
      if (bank == null) throw new IOException("Formato de banco de sons não reconhecido.");
      return bank;
    } catch (InvalidMidiDataException invalid) {
      throw new IOException("Banco de sons inválido.", invalid);
    }
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
