package org.arpeon.desktop;

import java.io.IOException;
import java.util.UUID;
import org.arpeon.importer.ImportDraft;
import org.arpeon.importer.LocalImporters;

/** One reviewable import draft per local session; no original bytes are persisted. */
final class ImportWorkspace {
  record Preview(String token, ImportDraft draft) {}

  private Preview pending;

  synchronized Preview preview(byte[] bytes, String filename, String kind) throws IOException {
    pending = null;
    if (filename == null || filename.length() > 200 || !filename.matches("[^/\\\\:]+"))
      throw new IllegalArgumentException("Use a short filename without a path");
    ImportDraft draft =
        switch (kind) {
          case "MIDI" -> LocalImporters.midi(bytes, filename);
          case "WAV" -> LocalImporters.wav(bytes, filename);
          case "NOTES", "SOLFEGE", "CIFRA_MELODICA" -> LocalImporters.text(bytes, filename, kind);
          default -> throw new IllegalArgumentException("Unsupported import format");
        };
    pending = new Preview(UUID.randomUUID().toString(), draft);
    return pending;
  }

  synchronized ImportDraft accept(String token) {
    if (pending == null || !pending.token().equals(token))
      throw new IllegalStateException("Import preview expired; analyze the source again");
    return pending.draft();
  }

  synchronized void clear() {
    pending = null;
  }
}
