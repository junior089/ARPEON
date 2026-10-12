package org.arpeon.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Application data never belongs to the installation directory. Existing profiles stay in place.
 */
final class UserDirectories {
  private UserDirectories() {}

  static Path data() {
    return resolve(
        Path.of(System.getProperty("user.home")),
        System.getProperty("os.name"),
        System.getenv(),
        System.getProperty("arpeon.dataDir"));
  }

  static Path resolve(Path home, String os, Map<String, String> environment, String override) {
    if (override != null && !override.isBlank())
      return Path.of(override).toAbsolutePath().normalize();
    Path legacy = home.resolve(".arpeon");
    if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) return legacy.toAbsolutePath().normalize();
    String platform = os.toLowerCase(Locale.ROOT);
    Path root;
    if (platform.contains("win")) {
      String local = environment.get("LOCALAPPDATA");
      root = local == null || local.isBlank() ? home.resolve("AppData/Local") : Path.of(local);
      root = root.resolve("ARPEON");
    } else if (platform.contains("mac")) {
      root = home.resolve("Library/Application Support/ARPEON");
    } else {
      String xdg = environment.get("XDG_DATA_HOME");
      root = xdg != null && Path.of(xdg).isAbsolute() ? Path.of(xdg) : home.resolve(".local/share");
      root = root.resolve("arpeon");
    }
    return root.toAbsolutePath().normalize();
  }

  static void ensureData() throws IOException {
    Path root = data();
    Files.createDirectories(root);
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("A pasta de dados do ARPEON não é um diretório seguro.");
  }
}
