package org.arpeon.desktop;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.arpeon.playback.MidiScorePlayer;
import org.arpeon.transport.PartAlignment;

/** Project preferences live beside the interoperable musical document. */
record RehearsalState(
    MidiScorePlayer.Settings playback,
    String viewMode,
    Set<String> hiddenParts,
    Map<String, PartOrigin> origins) {
  record PartOrigin(
      String filename,
      String sha256,
      String sourcePartId,
      List<PartAlignment.Anchor> anchors,
      boolean manual) {
    PartOrigin {
      if (filename == null
          || sha256 == null
          || !sha256.matches("[0-9a-f]{64}")
          || sourcePartId == null) throw new IllegalArgumentException("Invalid part provenance");
      anchors = List.copyOf(anchors);
    }
  }

  RehearsalState {
    if (!Set.of("full", "individual", "focus").contains(viewMode))
      throw new IllegalArgumentException("Invalid score view mode");
    hiddenParts = Set.copyOf(hiddenParts);
    origins = Map.copyOf(origins);
  }
}
