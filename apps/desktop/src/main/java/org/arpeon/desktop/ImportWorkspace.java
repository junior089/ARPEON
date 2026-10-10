package org.arpeon.desktop;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.arpeon.importer.ImportDraft;
import org.arpeon.importer.LocalImporters;

/** One reviewable import draft per local session; no original bytes are persisted. */
final class ImportWorkspace {
  record Preview(String token, ImportDraft draft, Source source) {}

  record Source(
      String providerId,
      String pageUrl,
      String fetchedAt,
      String licenseStatus,
      String licenseShortName,
      String licenseUrl,
      List<String> notes) {}

  private final WikimediaCommonsAdapter commons;
  private final LinkedHashSet<String> seen = new LinkedHashSet<>();
  private Preview pending;

  ImportWorkspace() {
    this(new WikimediaCommonsAdapter());
  }

  ImportWorkspace(WikimediaCommonsAdapter commons) {
    this.commons = commons;
  }

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
    pending = new Preview(UUID.randomUUID().toString(), draft, null);
    return pending;
  }

  synchronized Preview previewWeb(String url, String contact) throws IOException {
    pending = null;
    if (url == null || url.length() > 512)
      throw new IllegalArgumentException("Source URL is empty or too long");
    String key = WikimediaCommonsAdapter.canonicalKey(url);
    if (seen.contains(key))
      throw new IllegalArgumentException("This source was already analyzed in this session");
    if (seen.size() >= 100)
      throw new IllegalStateException("This import session reached the 100-source limit");
    var result = commons.preview(url, contact);
    seen.add(key);
    pending = new Preview(UUID.randomUUID().toString(), result.draft(), result.source());
    return pending;
  }

  synchronized Preview accept(String token) {
    if (pending == null || !pending.token().equals(token))
      throw new IllegalStateException("Import preview expired; analyze the source again");
    return pending;
  }

  synchronized void clear() {
    pending = null;
  }
}
