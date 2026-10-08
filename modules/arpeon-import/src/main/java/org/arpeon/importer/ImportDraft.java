package org.arpeon.importer;

import java.util.List;
import java.util.Objects;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.PitchClass;

/** Source-aware melody observations. Missing timing and octave stay missing until review. */
public record ImportDraft(
    String kind,
    String filename,
    String sha256,
    String parserVersion,
    String rhythm,
    List<DraftNote> notes,
    List<String> warnings) {
  public ImportDraft {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(filename);
    Objects.requireNonNull(sha256);
    Objects.requireNonNull(parserVersion);
    Objects.requireNonNull(rhythm);
    notes = List.copyOf(notes);
    warnings = List.copyOf(warnings);
    if (notes.isEmpty()) throw new IllegalArgumentException("No melody notes found");
  }

  public record DraftNote(
      PitchClass step,
      Accidental accidental,
      Integer octave,
      Long onsetTicks,
      Long durationTicks,
      double confidence) {
    public DraftNote {
      Objects.requireNonNull(step);
      Objects.requireNonNull(accidental);
      if (octave != null && (octave < -1 || octave > 9))
        throw new IllegalArgumentException("Octave out of range");
      if ((onsetTicks == null) != (durationTicks == null)
          || (onsetTicks != null && (onsetTicks < 0 || durationTicks <= 0)))
        throw new IllegalArgumentException("Invalid observed timing");
      if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)
        throw new IllegalArgumentException("Invalid confidence");
    }
  }
}
