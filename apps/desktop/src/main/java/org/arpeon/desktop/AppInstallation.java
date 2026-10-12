package org.arpeon.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class AppInstallation {
  private AppInstallation() {}

  static String version() throws IOException {
    String version = DesktopApplication.class.getPackage().getImplementationVersion();
    if (version == null) {
      var properties = new java.util.Properties();
      try (var input = AppInstallation.class.getResourceAsStream("/arpeon-version.properties")) {
        if (input == null) throw new IOException("Os metadados de versão não estão neste pacote.");
        properties.load(input);
      }
      version = properties.getProperty("version", "");
    }
    if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
      throw new IOException("Os metadados de versão estão inválidos.");
    return version;
  }

  static Path root() throws IOException {
    String configured = System.getProperty("arpeon.installDir");
    if (configured != null && !configured.isBlank())
      return Path.of(configured).toAbsolutePath().normalize();
    String launcher = System.getProperty("jpackage.app-path");
    if (launcher != null && !launcher.isBlank())
      return Path.of(launcher).toAbsolutePath().getParent();
    throw new IOException(
        "Use o instalador do ARPEON. Para desenvolvimento, configure -Darpeon.installDir com os"
            + " runtimes preparados.");
  }

  static Path component(String relative) throws IOException {
    Path directory = root().resolve(relative);
    if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory))
      throw new IOException("O pacote está incompleto: " + relative + ". Reinstale o ARPEON.");
    return directory;
  }
}
