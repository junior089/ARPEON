package org.arpeon.desktop;

import java.io.IOException;
import org.arpeon.importer.ImportDraft;

/** A user-selected source import, separate from search and bibliographic identification. */
interface ScoreSourceProvider {
  record Result(ImportDraft draft, ImportWorkspace.Source source, OriginalScore original) {
    Result(ImportDraft draft, ImportWorkspace.Source source) {
      this(draft, source, null);
    }
  }

  SourceRegistry.Provider descriptor();

  Result preview(String selection, String contact) throws IOException;
}
