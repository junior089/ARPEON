package org.arpeon.domain.instrument;

import java.util.Objects;
import org.arpeon.domain.pitch.Pitch;

public record InstrumentProfile(
    String id,
    String displayName,
    Pitch lowestWrittenPitch,
    Pitch highestWrittenPitch,
    int soundingTranspositionSemitones,
    Pitch lowestComfortablePitch,
    Pitch highestComfortablePitch,
    int midiProgram,
    String fingeringDatasetId) {
  public InstrumentProfile(
      String id,
      String displayName,
      Pitch lowestWrittenPitch,
      Pitch highestWrittenPitch,
      int soundingTranspositionSemitones,
      Pitch lowestComfortablePitch,
      Pitch highestComfortablePitch,
      int midiProgram) {
    this(
        id,
        displayName,
        lowestWrittenPitch,
        highestWrittenPitch,
        soundingTranspositionSemitones,
        lowestComfortablePitch,
        highestComfortablePitch,
        midiProgram,
        "");
  }

  public InstrumentProfile(
      String id,
      String displayName,
      Pitch lowestWrittenPitch,
      Pitch highestWrittenPitch,
      int soundingTranspositionSemitones) {
    this(
        id,
        displayName,
        lowestWrittenPitch,
        highestWrittenPitch,
        soundingTranspositionSemitones,
        lowestWrittenPitch,
        highestWrittenPitch,
        0,
        "");
  }

  public InstrumentProfile {
    if (id == null || id.isBlank() || displayName == null || displayName.isBlank())
      throw new IllegalArgumentException("Instrument identity required");
    Objects.requireNonNull(lowestWrittenPitch);
    Objects.requireNonNull(highestWrittenPitch);
    Objects.requireNonNull(lowestComfortablePitch);
    Objects.requireNonNull(highestComfortablePitch);
    if (soundingTranspositionSemitones < -127 || soundingTranspositionSemitones > 127)
      throw new IllegalArgumentException("Transposition outside MIDI range");
    if (lowestWrittenPitch.midiNumber() > highestWrittenPitch.midiNumber())
      throw new IllegalArgumentException("Inverted range");
    if (lowestWrittenPitch.midiNumber() + soundingTranspositionSemitones < 0
        || highestWrittenPitch.midiNumber() + soundingTranspositionSemitones > 127)
      throw new IllegalArgumentException("Sounding range outside MIDI");
    if (lowestComfortablePitch.midiNumber() < lowestWrittenPitch.midiNumber()
        || highestComfortablePitch.midiNumber() > highestWrittenPitch.midiNumber()
        || lowestComfortablePitch.midiNumber() > highestComfortablePitch.midiNumber())
      throw new IllegalArgumentException("Comfortable range outside playable range");
    if (midiProgram < 0 || midiProgram > 127)
      throw new IllegalArgumentException("Invalid MIDI program");
    Objects.requireNonNull(fingeringDatasetId);
  }

  public Pitch soundingPitch(Pitch writtenPitch) {
    if (soundingTranspositionSemitones == 0) return writtenPitch;
    return Pitch.fromMidiNumber(writtenPitch.midiNumber() + soundingTranspositionSemitones);
  }

  public Pitch writtenPitch(Pitch soundingPitch) {
    if (soundingTranspositionSemitones == 0) return soundingPitch;
    return Pitch.fromMidiNumber(soundingPitch.midiNumber() - soundingTranspositionSemitones);
  }

  public boolean inRange(Pitch writtenPitch) {
    return writtenPitch.midiNumber() >= lowestWrittenPitch.midiNumber()
        && writtenPitch.midiNumber() <= highestWrittenPitch.midiNumber();
  }

  public boolean comfortable(Pitch writtenPitch) {
    return writtenPitch.midiNumber() >= lowestComfortablePitch.midiNumber()
        && writtenPitch.midiNumber() <= highestComfortablePitch.midiNumber();
  }
}
