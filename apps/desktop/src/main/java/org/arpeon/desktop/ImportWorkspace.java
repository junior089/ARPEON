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

  record SourceMetadata(String title, String composer, String instrumentId) {}

  record Source(
      String providerId,
      String pageUrl,
      String fetchedAt,
      String licenseStatus,
      String licenseShortName,
      String licenseUrl,
      List<String> notes,
      SourceMetadata metadata) {
    Source(
        String providerId,
        String pageUrl,
        String fetchedAt,
        String licenseStatus,
        String licenseShortName,
        String licenseUrl,
        List<String> notes) {
      this(
          providerId, pageUrl, fetchedAt, licenseStatus, licenseShortName, licenseUrl, notes, null);
    }
  }

  private final ScoreSourceProvider commons;
  private final ScoreSourceProvider mutopia;
  private final LinkedHashSet<String> seen = new LinkedHashSet<>();
  private Preview pending;
  private OriginalScore pendingOriginal;

  ImportWorkspace() {
    this(new WikimediaCommonsAdapter());
  }

  ImportWorkspace(ScoreSourceProvider commons) {
    this(commons, new MutopiaAdapter());
  }

  ImportWorkspace(ScoreSourceProvider commons, ScoreSourceProvider mutopia) {
    this.commons = commons;
    this.mutopia = mutopia;
  }

  synchronized Preview preview(byte[] bytes, String filename, String kind) throws IOException {
    clear();
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
    clear();
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
    pendingOriginal = result.original();
    return pending;
  }

  synchronized Preview previewCatalogue(String providerId, String selection) throws IOException {
    clear();
    if (!"mutopia".equals(providerId) || !mutopia.descriptor().capabilities().catalogueImport())
      throw new IllegalArgumentException("Catálogo indisponível.");
    MutopiaCatalogue.entry(selection);
    String key = providerId + ":" + selection;
    if (seen.size() >= 100)
      throw new IllegalStateException("Limite de 100 fontes nesta sessão atingido.");
    var result = mutopia.preview(selection, "");
    seen.add(key);
    pending = new Preview(UUID.randomUUID().toString(), result.draft(), result.source());
    pendingOriginal = result.original();
    return pending;
  }

  synchronized OriginalScore original(String token) {
    accept(token);
    return pendingOriginal;
  }

  synchronized Preview accept(String token) {
    if (pending == null || !pending.token().equals(token))
      throw new IllegalStateException("Import preview expired; analyze the source again");
    return pending;
  }

  synchronized String notation(String token, int fallbackOctave, int equalNoteTicks) {
    var preview = accept(token);
    var score =
        org.arpeon.importer.DraftScoreBuilder.build(
            preview.draft(), fallbackOctave, equalNoteTicks);
    return new org.arpeon.musicxml.MusicXmlExporter().write(score);
  }

  synchronized void clear() {
    pending = null;
    pendingOriginal = null;
  }
}
