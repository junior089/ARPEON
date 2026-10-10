package org.arpeon.omr;

import java.io.IOException;

public interface OmrEngine {
  record InstallProgress(String phase, long downloadedBytes, long totalBytes) {}

  default boolean available() {
    return true;
  }

  default boolean installable() {
    return false;
  }

  default void install() throws IOException {
    throw new IOException("Automatic OMR installation is not supported on this platform");
  }

  default InstallProgress installProgress() {
    return new InstallProgress("idle", 0, 0);
  }

  OmrResult recognize(OmrInput input) throws IOException;
}
