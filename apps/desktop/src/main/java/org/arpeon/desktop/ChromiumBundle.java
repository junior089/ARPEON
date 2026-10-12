package org.arpeon.desktop;

import java.nio.file.Path;
import java.util.List;
import me.friwi.jcefmaven.CefAppBuilder;

/** Build-time entry point. Production startup uses only the extracted, shipped files. */
public final class ChromiumBundle {
  private ChromiumBundle() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected Chromium output directory");
    var builder = new CefAppBuilder();
    builder.setInstallDir(Path.of(args[0]).toFile());
    builder.setMirrors(List.of());
    builder.install();
  }
}
