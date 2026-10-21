package org.arpeon.desktop;

import java.util.List;

/** Reviewed files only; a catalogue entry never enables arbitrary downloads from a host. */
final class MutopiaCatalogue {
  static final String REVISION = "2144afd6f52d56c5b6995b8b589ef1268b3139f0";
  static final String PAGE = "https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=1528";
  static final String ARCHIVE =
      "https://www.mutopiaproject.org/ftp/BachJS/BWV1013/bwv1013/bwv1013-mids.zip";
  static final String ARCHIVE_SHA256 =
      "87923df54ebb8fad2782fba4146ee3c731f9cfe2a522af26e1ed07ab074f022e";
  static final String SOURCE =
      "https://github.com/MutopiaProject/MutopiaProject/blob/"
          + REVISION
          + "/ftp/BachJS/BWV1013/bwv1013/bwv1013.ly";
  static final String LICENSE_URL = "https://creativecommons.org/licenses/publicdomain/";

  record Entry(
      String id,
      String title,
      String composer,
      String instrument,
      String pageUrl,
      String sourceRevision,
      String sourceUrl,
      String license,
      String licenseUrl,
      String credit,
      String filename,
      String sha256,
      int byteSize) {}

  private static final List<Entry> ENTRIES =
      List.of(
          new Entry(
              "bach-bwv1013-allemande-1528",
              "Allemande — Partita em lá menor, BWV 1013",
              "Johann Sebastian Bach",
              "Flauta solo",
              PAGE,
              REVISION,
              SOURCE,
              "Public Domain",
              LICENSE_URL,
              "Mutopia Project · Jacob Shapiro · Mutopia-2017/10/31-1528",
              "bwv1013.mid",
              "43016ce88c25e691143d392bf31e6efd12b478ebe03ff378396a4eed97af8702",
              6011));

  private MutopiaCatalogue() {}

  static List<Entry> entries() {
    return ENTRIES;
  }

  static Entry entry(String id) {
    return ENTRIES.stream()
        .filter(entry -> entry.id().equals(id))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Seleção Mutopia não revisada nesta versão."));
  }
}
