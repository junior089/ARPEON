package org.arpeon.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.time.ScoreOffset;

/**
 * Explicit written-measure anchors; silence is added without shifting or deleting source events.
 */
public final class PartAlignment {
  public record Anchor(int sourceMeasure, int compositionMeasure) {
    public Anchor {
      if (sourceMeasure < 0 || compositionMeasure < 0)
        throw new IllegalArgumentException("Negative alignment anchor");
    }
  }

  private PartAlignment() {}

  public static Part align(
      Score composition,
      Score source,
      String sourcePartId,
      String id,
      List<Anchor> anchors,
      boolean manual) {
    if (anchors.isEmpty() || anchors.getFirst().sourceMeasure() != 0)
      throw new IllegalArgumentException("Defina a correspondência do primeiro compasso da parte.");
    if (anchors.size() > 64) throw new IllegalArgumentException("Too many alignment anchors");
    var part =
        source.parts().stream()
            .filter(p -> p.id().equals(sourcePartId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown source part"));
    var reference = composition.parts().getFirst().measures();
    if (!manual
        && (part.measures().size() != reference.size()
            || anchors.size() != 1
            || anchors.getFirst().compositionMeasure() != 0))
      throw new IllegalArgumentException("As extensões diferem. Revise os pontos de alinhamento.");
    var mapping = new int[part.measures().size()];
    int anchorIndex = 0, previousTarget = -1;
    for (int index = 0; index < mapping.length; index++) {
      if (anchorIndex + 1 < anchors.size() && anchors.get(anchorIndex + 1).sourceMeasure() == index)
        anchorIndex++;
      var anchor = anchors.get(anchorIndex);
      int target = Math.addExact(anchor.compositionMeasure(), index - anchor.sourceMeasure());
      if (target <= previousTarget || target >= reference.size())
        throw new IllegalArgumentException(
            "O alinhamento sobrepõe compassos ou ultrapassa a composição.");
      if (index > 0
          && anchorIndex > 0
          && anchors.get(anchorIndex).sourceMeasure() == index
          && target > previousTarget + 1
          && part.measures().get(index).voices().stream()
              .flatMap(v -> v.events().stream())
              .flatMap(e -> e.event().noteHeads().stream())
              .anyMatch(n -> n.tieStop()))
        throw new IllegalArgumentException("Uma âncora não pode interromper uma ligadura.");
      if (!compatible(reference.get(target), part.measures().get(index)))
        throw new IllegalArgumentException(
            "Fórmula, anacruse ou duração incompatível na posição " + (target + 1));
      mapping[index] = target;
      previousTarget = target;
    }
    for (int i = 0; i < anchors.size(); i++) {
      if (anchors.get(i).sourceMeasure() >= mapping.length
          || i > 0 && anchors.get(i).sourceMeasure() <= anchors.get(i - 1).sourceMeasure())
        throw new IllegalArgumentException("As âncoras devem seguir a ordem da parte.");
    }
    var mappedRepeats =
        source.repeatRegions().stream()
            .map(
                r ->
                    new org.arpeon.domain.score.RepeatRegion(
                        mapping[r.startMeasure()], mapping[r.endMeasure()], r.passes()))
            .toList();
    var mappedEndings =
        source.endingRegions().stream()
            .map(
                e ->
                    new org.arpeon.domain.score.EndingRegion(
                        mapping[e.startMeasure()], mapping[e.endMeasure()], e.passes()))
            .toList();
    if ((!manual || !mappedRepeats.isEmpty()) && !mappedRepeats.equals(composition.repeatRegions())
        || (!manual || !mappedEndings.isEmpty())
            && !mappedEndings.equals(composition.endingRegions()))
      throw new IllegalArgumentException(
          "Estruturas de repetição diferentes. Revise a fonte e o alinhamento.");
    double[] referenceTempos = new double[reference.size()];
    double tempo = 120;
    for (int index = 0; index < reference.size(); index++) {
      for (var existing : composition.parts())
        if (existing.measures().get(index).tempo().isPresent())
          tempo = existing.measures().get(index).tempo().orElseThrow().quarterNotesPerMinute();
      referenceTempos[index] = tempo;
    }
    var measures = new ArrayList<Measure>();
    int sourceIndex = 0;
    for (int index = 0; index < reference.size(); index++) {
      var target = reference.get(index);
      if (sourceIndex < mapping.length && mapping[sourceIndex] == index) {
        var imported = part.measures().get(sourceIndex++);
        var declared = imported.tempo();
        if (declared.isPresent()) {
          if (declared.orElseThrow().quarterNotesPerMinute() != referenceTempos[index])
            throw new IllegalArgumentException("Andamento conflitante na posição " + (index + 1));
        }
        measures.add(imported);
      } else {
        var duration =
            target.pickup()
                ? ScoreOffset.zero().distanceTo(end(target))
                : target.timeSignature().measureDuration();
        measures.add(
            new Measure(
                target.number(),
                target.timeSignature(),
                Optional.empty(),
                List.of(new Rest(duration)),
                target.pickup()));
      }
    }
    var clefs = new java.util.LinkedHashMap<Integer, org.arpeon.domain.score.Clef>();
    part.clefs()
        .forEach(
            (measureIndex, clef) -> clefs.put(measureIndex == 0 ? 0 : mapping[measureIndex], clef));
    return new Part(id, part.name(), measures, clefs);
  }

  private static ScoreOffset end(Measure measure) {
    return measure.voices().stream().map(v -> v.end()).max(ScoreOffset::compareTo).orElseThrow();
  }

  private static boolean compatible(Measure left, Measure right) {
    return left.timeSignature().equals(right.timeSignature())
        && left.pickup() == right.pickup()
        && (!left.pickup() || end(left).equals(end(right)));
  }
}
