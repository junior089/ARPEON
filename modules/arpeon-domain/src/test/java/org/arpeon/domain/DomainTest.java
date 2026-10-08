package org.arpeon.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.arpeon.domain.instrument.InstrumentProfile;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.ScoreEvent;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.TimeSignature;
import org.junit.jupiter.api.Test;

class DomainTest {
  @Test
  void concertPitchAndEnharmonicSpelling() {
    assertEquals(440, Pitch.fromMidiNumber(69).frequencyHz(440), 1e-10);
    assertEquals("C4", Pitch.fromMidiNumber(60).label());
    var flat = new Pitch(PitchClass.D, Accidental.FLAT, 4);
    var sharp = new Pitch(PitchClass.C, Accidental.SHARP, 4);
    assertNotEquals(flat, sharp);
    assertEquals(flat.midiNumber(), sharp.midiNumber());
    assertThrows(IllegalArgumentException.class, () -> Pitch.fromMidiNumber(128));
  }

  @Test
  void rationalDurationsRemainExact() {
    var triplet = new Duration(1, 12);
    assertEquals(new Duration(1, 4), triplet.plus(triplet).plus(triplet));
    assertEquals(new Duration(1, 4), new Duration(4, 16));
    assertThrows(IllegalArgumentException.class, () -> new Duration(1, 0));
  }

  @Test
  void rejectsIncompleteMeasuresExceptExplicitPickupsAndCopiesCollections() {
    var events = new ArrayList<ScoreEvent>(List.of(new Rest(new Duration(1, 4))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Measure("1", new TimeSignature(4, 4), Optional.empty(), events, false));
    var pickup = new Measure("0", new TimeSignature(4, 4), Optional.empty(), events, true);
    events.clear();
    assertEquals(1, pickup.events().size());
    assertThrows(UnsupportedOperationException.class, () -> pickup.events().clear());
    assertThrows(IllegalArgumentException.class, () -> new TimeSignature(3, 3));
  }

  @Test
  void writtenPitchAndSoundingPitchAreExplicit() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InstrumentProfile(
                "x", "x", Pitch.fromMidiNumber(0), Pitch.fromMidiNumber(127), Integer.MAX_VALUE));
    var clarinet =
        new InstrumentProfile(
            "bb-clarinet",
            "B-flat clarinet",
            Pitch.fromMidiNumber(50),
            Pitch.fromMidiNumber(94),
            -2);
    assertEquals(58, clarinet.soundingPitch(Pitch.fromMidiNumber(60)).midiNumber());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InstrumentProfile("x", "x", Pitch.fromMidiNumber(70), Pitch.fromMidiNumber(60), 0));
  }
}
