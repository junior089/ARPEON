package org.arpeon.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

final class ApplicationLog {
  private ApplicationLog() {}

  static FileHandler open() throws IOException {
    var directory = UserDirectories.data().resolve("logs");
    Files.createDirectories(directory);
    var handler =
        new FileHandler(directory.resolve("arpeon-%g.log").toString(), 1_048_576, 3, true);
    handler.setEncoding("UTF-8");
    handler.setFormatter(new SimpleFormatter());
    Logger.getLogger("").addHandler(handler);
    return handler;
  }
}
