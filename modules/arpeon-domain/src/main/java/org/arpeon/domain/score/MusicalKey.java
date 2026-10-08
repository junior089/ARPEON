package org.arpeon.domain.score;

import java.util.List;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;

/** Explicit tonic and mode; no automatic key detection or relative-key guessing. */
public record MusicalKey(String id, String label, int tonic, int fifths, boolean minor) {
  private static final List<MusicalKey> KEYS =
      List.of(
          major("C", 0, 0),
          major("G", 7, 1),
          major("D", 2, 2),
          major("A", 9, 3),
          major("E", 4, 4),
          major("B", 11, 5),
          major("F#", 6, 6),
          major("C#", 1, 7),
          major("Gb", 6, -6),
          major("Cb", 11, -7),
          major("Db", 1, -5),
          major("Ab", 8, -4),
          major("Eb", 3, -3),
          major("Bb", 10, -2),
          major("F", 5, -1),
          minor("A", 9, 0),
          minor("E", 4, 1),
          minor("B", 11, 2),
          minor("F#", 6, 3),
          minor("C#", 1, 4),
          minor("G#", 8, 5),
          minor("D#", 3, 6),
          minor("A#", 10, 7),
          minor("Eb", 3, -6),
          minor("Ab", 8, -7),
          minor("Bb", 10, -5),
          minor("F", 5, -4),
          minor("C", 0, -3),
          minor("G", 7, -2),
          minor("D", 2, -1));

  public MusicalKey {
    if (id == null
        || id.isBlank()
        || label == null
        || label.isBlank()
        || tonic < 0
        || tonic > 11
        || fifths < -7
        || fifths > 7) throw new IllegalArgumentException("Invalid musical key");
  }

  private static MusicalKey major(String tonicName, int tonic, int fifths) {
    return new MusicalKey(tonicName + "_MAJOR", tonicName + " maior", tonic, fifths, false);
  }

  private static MusicalKey minor(String tonicName, int tonic, int fifths) {
    return new MusicalKey(tonicName + "_MINOR", tonicName + " menor", tonic, fifths, true);
  }

  public static List<MusicalKey> choices() {
    return KEYS;
  }

  public static MusicalKey get(String id) {
    return KEYS.stream()
        .filter(key -> key.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown key"));
  }

  public static MusicalKey fromSignature(int fifths, boolean minor) {
    return KEYS.stream()
        .filter(key -> key.fifths() == fifths && key.minor() == minor)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported key signature"));
  }

  public MusicalKey transpose(int semitones) {
    int target = Math.floorMod(tonic + semitones, 12);
    return KEYS.stream()
        .filter(key -> key.minor() == minor && key.tonic() == target)
        .min(
            java.util.Comparator.comparingInt((MusicalKey key) -> Math.abs(key.fifths() - fifths))
                .thenComparingInt(key -> Math.abs(key.fifths())))
        .orElseThrow();
  }

  public Pitch spell(int midi) {
    if (midi < 0 || midi > 127) throw new IllegalArgumentException("Pitch outside MIDI");
    for (PitchClass step : PitchClass.values()) {
      int alter = signatureAlter(step);
      int octave = Math.floorDiv(midi - step.semitones() - alter, 12) - 1;
      if (Math.floorMod(step.semitones() + alter, 12) == midi % 12 && octave >= -1 && octave <= 9)
        return pitch(midi, step, alter);
    }
    PitchClass[] chromatic =
        fifths < 0
            ? new PitchClass[] {
              PitchClass.C, PitchClass.D, PitchClass.D, PitchClass.E, PitchClass.E,
              PitchClass.F, PitchClass.G, PitchClass.G, PitchClass.A, PitchClass.A,
              PitchClass.B, PitchClass.B
            }
            : new PitchClass[] {
              PitchClass.C, PitchClass.C, PitchClass.D, PitchClass.D, PitchClass.E,
              PitchClass.F, PitchClass.F, PitchClass.G, PitchClass.G, PitchClass.A,
              PitchClass.A, PitchClass.B
            };
    PitchClass step = chromatic[midi % 12];
    int alter = midi % 12 - step.semitones();
    return pitch(midi, step, alter);
  }

  private int signatureAlter(PitchClass step) {
    PitchClass[] sharps = {
      PitchClass.F,
      PitchClass.C,
      PitchClass.G,
      PitchClass.D,
      PitchClass.A,
      PitchClass.E,
      PitchClass.B
    };
    PitchClass[] flats = {
      PitchClass.B,
      PitchClass.E,
      PitchClass.A,
      PitchClass.D,
      PitchClass.G,
      PitchClass.C,
      PitchClass.F
    };
    for (int i = 0; i < Math.abs(fifths); i++)
      if ((fifths > 0 ? sharps : flats)[i] == step) return Integer.signum(fifths);
    return 0;
  }

  private static Pitch pitch(int midi, PitchClass step, int alter) {
    int octave = Math.floorDiv(midi - step.semitones() - alter, 12) - 1;
    return new Pitch(step, Accidental.fromSemitones(alter), octave);
  }
}
