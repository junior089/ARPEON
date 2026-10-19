package org.arpeon.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ProfileMigration {
  private static final List<String> SCOPES =
      List.of(
          "projects",
          "preferences.json",
          "practice-history.json",
          "analysis-history.json",
          "soundbanks");

  private ProfileMigration() {}

  static void initialize(Path root, String profileId, byte[] account) throws IOException {
    Files.createDirectories(root);
    LocalAccountStore.requireDirectory(root);
    var files = new ArrayList<Path>();
    long bytes = 0;
    for (String scope : SCOPES) {
      Path source = root.resolve(scope);
      if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
      try (var paths = Files.walk(source, 9)) {
        var iterator = paths.iterator();
        int entries = 0;
        while (iterator.hasNext()) {
          Path path = iterator.next();
          if (++entries > 40000)
            throw new IOException("A biblioteca excede o limite de arquivos da migração.");
          if (Files.isSymbolicLink(path))
            throw new IOException("A migração não aceita atalhos simbólicos nos dados de perfil.");
          if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            if (root.relativize(path).getNameCount() > 8)
              throw new IOException("A biblioteca excede a profundidade permitida para migração.");
          } else {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              throw new IOException("A biblioteca contém um arquivo não suportado.");
            files.add(path);
            bytes = Math.addExact(bytes, Files.size(path));
            if (files.size() > 20000 || bytes > 64L * 1024 * 1024 * 1024)
              throw new IOException("A biblioteca excede os limites da migração local.");
          }
        }
      }
    }
    if (Files.getFileStore(root).getUsableSpace() < bytes + 1024 * 1024)
      throw new IOException("Não há espaço para criar uma cópia recuperável da biblioteca.");
    Path staging = root.resolve(".identity-staging-" + UUID.randomUUID());
    Files.createDirectory(staging);
    Path target = staging.resolve("profiles").resolve(profileId);
    Files.createDirectories(target);
    for (Path source : files) {
      Path copy = target.resolve(root.relativize(source));
      Files.createDirectories(copy.getParent());
      byte[] before = digest(source);
      Files.copy(source, copy, LinkOption.NOFOLLOW_LINKS);
      if (Files.isSymbolicLink(copy)) throw new IOException("Um arquivo mudou durante a migração.");
      if (!MessageDigest.isEqual(before, digest(copy))
          || !MessageDigest.isEqual(before, digest(source)))
        throw new IOException(
            "Um arquivo mudou durante a migração. Os dados originais foram preservados.");
      try (var channel =
          java.nio.channels.FileChannel.open(copy, java.nio.file.StandardOpenOption.WRITE)) {
        channel.force(true);
      }
    }
    LocalAccountStore.writeFile(staging.resolve("account.json"), account);
    try {
      Files.move(staging, root.resolve("identity"), StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
      Files.move(staging, root.resolve("identity"));
    }
  }

  private static byte[] digest(Path file) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[65536];
        for (int read; (read = stream.read(buffer)) != -1; ) digest.update(buffer, 0, read);
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
