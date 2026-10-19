package org.arpeon.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.TimeSignature;
import org.junit.jupiter.api.Test;

class PartAlignmentTest {
  @Test
  void insertedSilenceCoversEveryStaffOfTheImportedInstrument() {
    var original = score("S", 1, false).parts().getFirst();
    var piano =
        new Part(
            original.id(),
            "Piano",
            original.measures(),
            java.util.Map.of(),
            2,
            java.util.Map.of(2, java.util.Map.of(0, new org.arpeon.domain.score.Clef("F", 4, 0))));
    var aligned =
        PartAlignment.align(
            score("C", 3, false),
            new Score("Piano", List.of(piano)),
            "P1",
            "P2",
            List.of(new PartAlignment.Anchor(0, 1)),
            true);
    assertEquals(2, aligned.staves());
    assertEquals(
        List.of(1, 2),
        aligned.measures().getFirst().voices().stream()
            .map(v -> v.events().getFirst().event().staff())
            .toList());
    assertEquals("F", aligned.additionalClefs().get(2).get(0).sign());
  }

  private Score score(String label, int count, boolean pickup) {
    var measures = new java.util.ArrayList<Measure>();
    for (int i = 0; i < count; i++)
      measures.add(
          new Measure(
              label + i,
              new TimeSignature(4, 4),
              Optional.empty(),
              List.of(
                  new Note(
                      Pitch.fromMidiNumber(60 + i), new Duration(pickup && i == 0 ? 1 : 4, 4))),
              pickup && i == 0));
    return new Score("Alignment", List.of(new Part("P1", "Part", measures)));
  }

  @Test
  void alignsByMusicalStructureDespiteDifferentMeasureLabelsAndDensity() {
    var composition = score("A", 3, false);
    var imported = score("101-", 3, false);
    var aligned =
        PartAlignment.align(
            composition, imported, "P1", "P2", List.of(new PartAlignment.Anchor(0, 0)), false);
    assertEquals(imported.parts().getFirst().measures(), aligned.measures());
    var timeline =
        new ScoreTimeline(new Score("Ensemble", List.of(composition.parts().getFirst(), aligned)));
    assertEquals(6, timeline.notes().size());
    assertEquals(0, timeline.notes().get(1).startTick());
  }

  @Test
  void requiresExplicitAnchorsForLateEntriesAndAddsOnlySilence() {
    var composition = score("C", 5, false);
    var source = score("S", 2, false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PartAlignment.align(
                composition, source, "P1", "P2", List.of(new PartAlignment.Anchor(0, 0)), false));
    var aligned =
        PartAlignment.align(
            composition,
            source,
            "P1",
            "P2",
            List.of(new PartAlignment.Anchor(0, 1), new PartAlignment.Anchor(1, 3)),
            true);
    assertTrue(aligned.measures().getFirst().events().getFirst() instanceof Rest);
    assertEquals(source.parts().getFirst().measures().getFirst(), aligned.measures().get(1));
    assertTrue(aligned.measures().get(2).events().getFirst() instanceof Rest);
    assertEquals(source.parts().getFirst().measures().get(1), aligned.measures().get(3));
  }

  @Test
  void rejectsPickupMismatchOverlappingAnchorsAndMaterialBeyondComposition() {
    var composition = score("C", 3, false);
    var source = score("S", 3, true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PartAlignment.align(
                composition, source, "P1", "P2", List.of(new PartAlignment.Anchor(0, 0)), true));
    var regular = score("S", 3, false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PartAlignment.align(
                composition,
                regular,
                "P1",
                "P2",
                List.of(new PartAlignment.Anchor(0, 0), new PartAlignment.Anchor(1, 0)),
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PartAlignment.align(
                composition, regular, "P1", "P2", List.of(new PartAlignment.Anchor(0, 1)), true));
  }
}
