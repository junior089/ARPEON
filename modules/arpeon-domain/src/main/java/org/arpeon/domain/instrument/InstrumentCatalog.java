package org.arpeon.domain.instrument;

import java.util.List;
import org.arpeon.domain.pitch.Pitch;

/** Initial selectable voices. Only flute currently has a documented instrument-specific range. */
public final class InstrumentCatalog {
  private static final List<InstrumentProfile> CORE =
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

  private static final List<InstrumentProfile> PROFILES = catalog();

  private static List<InstrumentProfile> catalog() {
    var result = new java.util.ArrayList<>(CORE);
    String[][] voices = {
      {"piccolo", "Flautim", "72", "12"}, {"alto-sax", "Saxofone alto", "65", "-9"},
      {"trumpet", "Trompete em Si bemol", "56", "-2"}, {"violin", "Violino", "40", "0"},
      {"viola", "Viola", "41", "0"}, {"cello", "Violoncelo", "42", "0"},
      {"double-bass", "Contrabaixo", "43", "-12"}, {"piano", "Piano", "0", "0"},
      {"harpsichord", "Cravo", "6", "0"}, {"guitar", "Violão", "24", "-12"},
      {"electric-guitar", "Guitarra", "27", "-12"}, {"electric-bass", "Baixo elétrico", "33", "-12"}
    };
    for (var voice : voices)
      result.add(
          profile(voice[0], voice[1], Integer.parseInt(voice[2]), Integer.parseInt(voice[3])));
    for (int program = 0; program < 128; program++)
      result.add(profile("gm-" + program, "General MIDI " + (program + 1), program, 0));
    return List.copyOf(result);
  }

  private static InstrumentProfile profile(String id, String name, int program, int transpose) {
    int low = Math.max(0, -transpose), high = Math.min(127, 127 - transpose);
    return new InstrumentProfile(
        id, name, pitch(low), pitch(high), transpose, pitch(low), pitch(high), program);
  }

  public static InstrumentProfile detect(String name) {
    String normalized =
        java.text.Normalizer.normalize(
                name.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
    int program =
        switch (normalized.strip()) {
          case "flute", "flauta", "flauta transversal" -> 73;
          case "piccolo", "flautim" -> 72;
          case "clarinet", "clarinete" -> 71;
          case "violin", "violino", "violin i", "violin ii", "violino i", "violino ii" -> 40;
          case "viola" -> 41;
          case "cello", "violoncello", "violoncelo" -> 42;
          case "piano" -> 0;
          case "harpsichord", "cravo" -> 6;
          case "trumpet", "trompete" -> 56;
          case "alto sax", "saxofone alto" -> 65;
          case "double bass", "contrabass", "contrabaixo" -> 43;
          case "guitar", "violao" -> 24;
          case "electric guitar", "guitarra" -> 27;
          case "electric bass", "baixo eletrico" -> 33;
          default -> -1;
        };
    return program < 0 ? get("concert") : forProgram(program);
  }

  public static InstrumentProfile forProgram(int program) {
    if (program < 0 || program > 127)
      throw new IllegalArgumentException("Invalid General MIDI program");
    return PROFILES.stream()
        .filter(
            p ->
                p.midiProgram() == program
                    && p.soundingTranspositionSemitones() == 0
                    && !p.id().equals("concert"))
        .findFirst()
        .orElseThrow();
  }

  public static InstrumentProfile withTranspose(
      InstrumentProfile base, int chromatic, int diatonic) {
    if (base.soundingTranspositionSemitones() == chromatic && diatonic(base) == diatonic)
      return base;
    for (var known : PROFILES)
      if (known.midiProgram() == base.midiProgram()
          && known.soundingTranspositionSemitones() == chromatic
          && diatonic(known) == diatonic) return known;
    return profile(
        "gm-" + base.midiProgram() + "-t" + chromatic + "-d" + diatonic,
        base.displayName(),
        base.midiProgram(),
        chromatic);
  }

  public static int diatonic(InstrumentProfile profile) {
    if (profile.id().contains("-d"))
      return Integer.parseInt(profile.id().substring(profile.id().lastIndexOf("-d") + 2));
    return switch (profile.soundingTranspositionSemitones()) {
      case -2 -> -1;
      case -9 -> -5;
      case -12 -> -7;
      case 12 -> 7;
      default -> 0;
    };
  }

  private InstrumentCatalog() {}

  public static List<InstrumentProfile> profiles() {
    return PROFILES;
  }

  public static InstrumentProfile get(String id) {
    if (id != null && id.matches("gm-[0-9]{1,3}-t-?[0-9]{1,3}-d-?[0-9]{1,3}")) {
      var fields = id.split("-t", 2);
      var offsets = fields[1].split("-d", 2);
      return withTranspose(
          forProgram(Integer.parseInt(fields[0].substring(3))),
          Integer.parseInt(offsets[0]),
          Integer.parseInt(offsets[1]));
    }
    return PROFILES.stream()
        .filter(profile -> profile.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown instrument profile"));
  }

  private static Pitch pitch(int midi) {
    return Pitch.fromMidiNumber(midi);
  }
}
