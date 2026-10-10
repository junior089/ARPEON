package org.arpeon.omr;

import java.util.List;
import org.arpeon.musicxml.MusicXmlImporter;

public record OmrResult(
    MusicXmlImporter.ImportedScore imported,
    String sourceSha256,
    String sourceFilename,
    int pages,
    List<String> warnings,
    List<Integer> pagesRequiringReview,
    List<String> unsupportedSymbols) {
  public OmrResult {
    warnings = List.copyOf(warnings);
    pagesRequiringReview = List.copyOf(pagesRequiringReview);
    unsupportedSymbols = List.copyOf(unsupportedSymbols);
  }
}
