package org.arpeon.domain.pitch;

import java.util.Objects;

public record Pitch(PitchClass pitchClass, Accidental accidental, int octave) {
  public Pitch {
    Objects.requireNonNull(pitchClass);
    Objects.requireNonNull(accidental);
    if (octave < -1 || octave > 9) throw new IllegalArgumentException("Octave out of range");
    int midi = (octave + 1) * 12 + pitchClass.semitones() + accidental.semitones();
    if (midi < 0 || midi > 127) throw new IllegalArgumentException("Pitch outside MIDI range");
  }

  public int midiNumber() {
    return (octave + 1) * 12 + pitchClass.semitones() + accidental.semitones();
  }

  public double frequencyHz(double a4Hz) {
    if (!Double.isFinite(a4Hz) || a4Hz <= 0)
      throw new IllegalArgumentException("Invalid reference");
    return a4Hz * Math.pow(2, (midiNumber() - 69) / 12.0);
  }

  public static Pitch fromMidiNumber(int midi) {
    if (midi < 0 || midi > 127) throw new IllegalArgumentException("MIDI number out of range");
    PitchClass[] names = {
      PitchClass.C,
      PitchClass.C,
      PitchClass.D,
      PitchClass.D,
      PitchClass.E,
      PitchClass.F,
      PitchClass.F,
      PitchClass.G,
      PitchClass.G,
      PitchClass.A,
      PitchClass.A,
      PitchClass.B
    };
    var name = names[midi % 12];
    return new Pitch(name, Accidental.fromSemitones(midi % 12 - name.semitones()), midi / 12 - 1);
  }

  public String label() {
    String suffix =
        switch (accidental) {
          case DOUBLE_FLAT -> "bb";
          case FLAT -> "b";
          case NATURAL -> "";
          case SHARP -> "#";
          case DOUBLE_SHARP -> "##";
        };
    return pitchClass.name() + suffix + octave;
  }
}
