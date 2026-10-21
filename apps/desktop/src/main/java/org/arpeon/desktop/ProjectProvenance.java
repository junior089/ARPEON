package org.arpeon.desktop;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.arpeon.musicxml.MusicXmlImporter;

/** Evidence about the initial import, never a claim that matching titles identify one work. */
record ProjectProvenance(
    int schemaVersion,
    Work work,
    Edition edition,
    Arrangement arrangement,
    SourceAsset sourceAsset,
    RightsRecord rights) {
  record ExternalIdentifier(String namespace, String value) {}

  record Work(
      String id, String title, String composer, List<ExternalIdentifier> externalIdentifiers) {
    Work {
      externalIdentifiers = List.copyOf(externalIdentifiers);
    }
  }

  record Edition(
      String id, String workId, String title, List<ExternalIdentifier> externalIdentifiers) {
    Edition {
      externalIdentifiers = List.copyOf(externalIdentifiers);
    }
  }

  record Part(String id, String name, String instrumentId) {}

  record Arrangement(String id, String workId, List<Part> parts) {
    Arrangement {
      parts = List.copyOf(parts);
    }
  }

  record SourceAsset(
      String id,
      String filename,
      String sha256,
      String format,
      long byteSize,
      boolean retained,
      List<ExternalIdentifier> externalIdentifiers) {
    SourceAsset {
      externalIdentifiers = List.copyOf(externalIdentifiers);
    }
  }

  record RightsRecord(
      String status,
      String declaredLicense,
      String licenseUrl,
      String derivation,
      String redistribution) {}

  static ProjectProvenance imported(
      MusicXmlImporter.ImportedScore imported,
      String kind,
      ImportWorkspace.Source source,
      OriginalScore original) {
    String workId = UUID.randomUUID().toString();
    var parts =
        imported.score().parts().stream()
            .map(
                part ->
                    new Part(
                        part.id(),
                        part.name(),
                        imported.instruments().getOrDefault(part.id(), "concert")))
            .toList();
    return new ProjectProvenance(
        1,
        new Work(workId, imported.score().title(), imported.score().composer(), List.of()),
        null,
        new Arrangement(UUID.randomUUID().toString(), workId, parts),
        new SourceAsset(
            "sha256:" + imported.sha256(),
            imported.sourceFilename(),
            imported.sha256(),
            original == null ? kind : original.format(),
            original == null ? 0 : original.size(),
            original != null,
            source == null
                ? List.of()
                : List.of(new ExternalIdentifier(source.providerId() + "-file", source.pageUrl()))),
        new RightsRecord(
            "NOT_REVIEWED",
            source == null ? null : source.licenseShortName(),
            source == null ? null : source.licenseUrl(),
            "UNKNOWN",
            "UNKNOWN"));
  }

  void validate(String filename, String sourceHash) throws IOException {
    try {
      if (schemaVersion != 1
          || work == null
          || arrangement == null
          || sourceAsset == null
          || rights == null
          || !uuid(work.id())
          || !uuid(arrangement.id())
          || !work.id().equals(arrangement.workId())
          || !text(work.title(), 500)
          || !text(work.composer(), 500)
          || !identifiers(work.externalIdentifiers())
          || arrangement.parts() == null
          || arrangement.parts().isEmpty()
          || arrangement.parts().size() > MusicXmlImporter.MAX_PARTS
          || arrangement.parts().stream()
              .anyMatch(
                  part ->
                      part == null
                          || !text(part.id(), 200)
                          || !text(part.name(), 500)
                          || !text(part.instrumentId(), 100))
          || arrangement.parts().stream().map(Part::id).distinct().count()
              != arrangement.parts().size()
          || !filename.equals(sourceAsset.filename())
          || !sourceHash.equals(sourceAsset.sha256())
          || !sourceAsset.id().equals("sha256:" + sourceHash)
          || !text(sourceAsset.format(), 40)
          || !identifiers(sourceAsset.externalIdentifiers())
          || sourceAsset.byteSize() < 0
          || sourceAsset.byteSize() > MusicXmlImporter.MAX_BYTES
          || (sourceAsset.retained()
              ? sourceAsset.byteSize() == 0
                  || !List.of("MUSICXML", "MXL", "MSCZ", "MIDI").contains(sourceAsset.format())
              : sourceAsset.byteSize() != 0)
          || !"NOT_REVIEWED".equals(rights.status())
          || !"UNKNOWN".equals(rights.derivation())
          || !"UNKNOWN".equals(rights.redistribution())
          || (rights.declaredLicense() != null && !text(rights.declaredLicense(), 1000))
          || (rights.licenseUrl() != null && !https(rights.licenseUrl())))
        throw new IllegalArgumentException();
      if (edition != null
          && (!uuid(edition.id())
              || !work.id().equals(edition.workId())
              || !text(edition.title(), 500)
              || !identifiers(edition.externalIdentifiers()))) throw new IllegalArgumentException();
    } catch (RuntimeException invalid) {
      throw new IOException("Proveniência do projeto inválida.", invalid);
    }
  }

  private static boolean identifiers(List<ExternalIdentifier> identifiers) {
    return identifiers != null
        && identifiers.size() <= 16
        && identifiers.stream()
            .allMatch(id -> id != null && text(id.namespace(), 100) && text(id.value(), 1000));
  }

  private static boolean text(String value, int max) {
    return value != null
        && value.length() <= max
        && value
            .codePoints()
            .noneMatch(
                code ->
                    Character.isISOControl(code) && code != '\n' && code != '\r' && code != '\t');
  }

  private static boolean uuid(String value) {
    return value != null && UUID.fromString(value).toString().equals(value);
  }

  private static boolean https(String value) {
    var uri = java.net.URI.create(value);
    return value.length() <= 2000
        && "https".equals(uri.getScheme())
        && uri.getHost() != null
        && uri.getUserInfo() == null;
  }

  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
