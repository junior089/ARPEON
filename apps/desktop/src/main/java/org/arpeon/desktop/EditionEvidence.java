package org.arpeon.desktop;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Append-only local bibliographic declaration; it never changes initial evidence or rights. */
record EditionEvidence(
    String id,
    String sourceAssetId,
    ProjectProvenance.Edition edition,
    String citation,
    String referenceUrl,
    String recordedAt) {
  static final int MAX_ENTRIES = 16;

  static EditionEvidence record(
      ProjectProvenance source, String title, String citation, String referenceUrl) {
    if (source == null)
      throw new IllegalStateException(
          "Este projeto não possui origem registrada. Reimporte o arquivo antes de identificar a"
              + " edição.");
    title = text(title, 200, false);
    citation = text(citation, 1000, true);
    referenceUrl = text(referenceUrl, 1000, false);
    if (!referenceUrl.isEmpty() && !safeReference(referenceUrl))
      throw new IllegalArgumentException(
          "Use uma referência HTTPS pública, sem credenciais, porta, consulta ou fragmento. A"
              + " referência não será acessada pelo ARPEON.");
    var edition =
        title.isEmpty()
            ? null
            : new ProjectProvenance.Edition(
                UUID.randomUUID().toString(), source.work().id(), title, List.of());
    return new EditionEvidence(
        UUID.randomUUID().toString(),
        source.sourceAsset().id(),
        edition,
        citation,
        referenceUrl,
        Instant.now().toString());
  }

  static void validate(List<EditionEvidence> entries, ProjectProvenance source) throws IOException {
    try {
      if (entries == null || entries.size() > MAX_ENTRIES || (!entries.isEmpty() && source == null))
        throw new IllegalArgumentException();
      var ids = new java.util.HashSet<String>();
      for (var entry : entries) {
        if (entry == null
            || !ids.add(entry.id())
            || !uuid(entry.id())
            || !entry.sourceAssetId().equals(source.sourceAsset().id())
            || !text(entry.citation(), 1000, true).equals(entry.citation())
            || !text(entry.referenceUrl(), 1000, false).equals(entry.referenceUrl())
            || (!entry.referenceUrl().isEmpty() && !safeReference(entry.referenceUrl())))
          throw new IllegalArgumentException();
        if (entry.edition() != null
            && (!uuid(entry.edition().id())
                || !source.work().id().equals(entry.edition().workId())
                || !text(entry.edition().title(), 200, true).equals(entry.edition().title())
                || !entry.edition().externalIdentifiers().isEmpty()))
          throw new IllegalArgumentException();
        Instant.parse(entry.recordedAt());
      }
    } catch (RuntimeException invalid) {
      throw new IOException("Histórico de identificação da edição inválido.", invalid);
    }
  }

  private static String text(String value, int maximum, boolean required) {
    if (value == null) throw new IllegalArgumentException("Preencha a referência da edição.");
    String result = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    if (result.length() > maximum
        || (required && result.isEmpty())
        || result
            .codePoints()
            .anyMatch(
                code ->
                    Character.isISOControl(code) || Character.getType(code) == Character.FORMAT))
      throw new IllegalArgumentException(
          "Use texto sem quebras de linha ou caracteres de controle, dentro do limite indicado.");
    return result;
  }

  private static boolean uuid(String value) {
    return value != null && UUID.fromString(value).toString().equals(value);
  }

  private static boolean safeReference(String value) {
    try {
      var uri = java.net.URI.create(value);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getPort() == -1
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null;
    } catch (RuntimeException invalid) {
      return false;
    }
  }
}
