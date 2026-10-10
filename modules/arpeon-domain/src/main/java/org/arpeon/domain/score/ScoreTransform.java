package org.arpeon.domain.score;

import java.util.ArrayList;
import java.util.List;
import org.arpeon.domain.instrument.InstrumentProfile;
import org.arpeon.domain.pitch.Pitch;

/** Pure pitch transformations. Duration and measure structure are retained by ScoreDocument. */
public final class ScoreTransform {
  public record RangeWarning(
      String partId, int measureIndex, String voiceId, int eventIndex, String pitch, String kind) {
    public RangeWarning(
        String partId, int measureIndex, int eventIndex, String pitch, String kind) {
      this(partId, measureIndex, "1", eventIndex, pitch, kind);
    }
  }

  public record OctavePreview(int semitones, List<RangeWarning> before, List<RangeWarning> after) {}

  private ScoreTransform() {}

  public static Score transpose(Score score, String partId, int semitones, MusicalKey spelling) {
    if (semitones < -48 || semitones > 48)
      throw new IllegalArgumentException("Interval outside four octaves");
    return ScoreDocument.mapNotes(
        score,
        partId,
        pitch -> {
          int midi = pitch.midiNumber() + semitones;
          if (spelling == null && semitones % 12 == 0)
            return new Pitch(
                pitch.pitchClass(), pitch.accidental(), pitch.octave() + semitones / 12);
          return spelling == null ? Pitch.fromMidiNumber(midi) : spelling.spell(midi);
        });
  }

  public static Score toKey(Score score, String partId, MusicalKey source, MusicalKey target) {
    if (source.minor() != target.minor())
      throw new IllegalArgumentException("Choose source and target keys with the same mode");
    int shift = Math.floorMod(target.tonic() - source.tonic() + 6, 12) - 6;
    return transpose(score, partId, shift, target);
  }

  public static Score convertPitch(
      Score score, String partId, InstrumentProfile profile, boolean toSounding) {
    return ScoreDocument.mapNotes(
        score,
        partId,
        pitch -> toSounding ? profile.soundingPitch(pitch) : profile.writtenPitch(pitch));
  }

  public static List<RangeWarning> warnings(Score score, String partId, InstrumentProfile profile) {
    var result = new ArrayList<RangeWarning>();
    boolean found = false;
    for (Part part : score.parts()) {
      if (!part.id().equals(partId)) continue;
      found = true;
      for (int m = 0; m < part.measures().size(); m++)
        for (var voice : part.measures().get(m).voices())
          for (int e = 0; e < voice.events().size(); e++) {
            for (Note note : voice.events().get(e).event().noteHeads()) {
              var pitch = note.writtenPitch();
              if (!profile.inRange(pitch))
                result.add(
                    new RangeWarning(partId, m, voice.id(), e, pitch.label(), "OUTSIDE_RANGE"));
              else if (!profile.comfortable(pitch))
                result.add(
                    new RangeWarning(partId, m, voice.id(), e, pitch.label(), "ABOVE_COMFORT"));
            }
          }
    }
    if (!found) throw new IllegalArgumentException("Unknown part");
    return List.copyOf(result);
  }

  public static OctavePreview previewOctave(
      Score score, String partId, InstrumentProfile profile, int semitones) {
    if (semitones != -12 && semitones != 0 && semitones != 12)
      throw new IllegalArgumentException("Choose one octave up or down");
    Score candidate = transpose(score, partId, semitones, null);
    return new OctavePreview(
        semitones, warnings(score, partId, profile), warnings(candidate, partId, profile));
  }
}
