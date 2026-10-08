package org.arpeon.domain.instrument;

import java.util.List;
import org.arpeon.domain.pitch.Pitch;

/** Initial selectable voices. Only flute currently has a documented instrument-specific range. */
public final class InstrumentCatalog {
  private static final List<InstrumentProfile> PROFILES =
      List.of(
          new InstrumentProfile(
              "flute",
              "Flauta transversal",
              pitch(60),
              pitch(96),
              0,
              pitch(60),
              pitch(84),
              73,
              "flute-cc0-chart-v1"),
          new InstrumentProfile(
              "bb-clarinet",
              "Clarinete em Si bemol",
              pitch(2),
              pitch(127),
              -2,
              pitch(2),
              pitch(127),
              71),
          new InstrumentProfile(
              "concert", "Som genérico", pitch(0), pitch(127), 0, pitch(0), pitch(127), 0));

  private InstrumentCatalog() {}

  public static List<InstrumentProfile> profiles() {
    return PROFILES;
  }

  public static InstrumentProfile get(String id) {
    return PROFILES.stream()
        .filter(profile -> profile.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown instrument profile"));
  }

  private static Pitch pitch(int midi) {
    return Pitch.fromMidiNumber(midi);
  }
}
