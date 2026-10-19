package org.arpeon.domain.score;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.arpeon.domain.pitch.Pitch;

public record Part(
    String id,
    String name,
    List<Measure> measures,
    Map<Integer, Clef> clefs,
    int staves,
    Map<Integer, Map<Integer, Clef>> additionalClefs) {
  public Part(String id, String name, List<Measure> measures, Map<Integer, Clef> clefs) {
    this(id, name, measures, clefs, 1, Map.of());
  }

  public Part(String id, String name, List<Measure> measures) {
    this(id, name, measures, Map.of());
  }

  public Part {
    if (id == null || id.isBlank() || name == null || name.isBlank())
      throw new IllegalArgumentException("Part identity required");
    measures = List.copyOf(measures);
    clefs = Map.copyOf(clefs);
    if (staves < 1 || staves > 8)
      throw new IllegalArgumentException("Use 1–8 staves per instrument");
    var extra = new LinkedHashMap<Integer, Map<Integer, Clef>>();
    for (var entry : additionalClefs.entrySet()) {
      if (entry.getKey() < 2 || entry.getKey() > staves)
        throw new IllegalArgumentException("Invalid clef staff");
      for (int index : entry.getValue().keySet())
        if (index < 0 || index >= measures.size())
          throw new IllegalArgumentException("Clef outside part");
      extra.put(entry.getKey(), Map.copyOf(entry.getValue()));
    }
    additionalClefs = Map.copyOf(extra);
    for (int measureIndex : clefs.keySet())
      if (measureIndex < 0 || measureIndex >= measures.size())
        throw new IllegalArgumentException("Clef outside part");
    if (measures.isEmpty()) throw new IllegalArgumentException("Part must contain measures");
    Map<String, Map<Pitch, Note>> previousTies = Map.of();
    for (Measure measure : measures) {
      var voiceIds =
          measure.voices().stream().map(Voice::id).collect(java.util.stream.Collectors.toSet());
      if (!voiceIds.containsAll(previousTies.keySet()))
        throw new IllegalArgumentException("Tie cannot continue into a missing voice");
      var nextMeasureTies = new LinkedHashMap<String, Map<Pitch, Note>>();
      for (Voice voice : measure.voices()) {
        Map<Pitch, Note> voicePrevious = previousTies.getOrDefault(voice.id(), Map.of());
        for (TimedEvent timed : voice.events()) {
          if (timed.event().staff() > staves
              || timed.event().noteHeads().stream().anyMatch(n -> n.staff() > staves))
            throw new IllegalArgumentException("Note or rest outside declared staves");
          var currentHeads = new LinkedHashMap<Pitch, Note>();
          for (Note note : timed.event().noteHeads()) currentHeads.put(note.writtenPitch(), note);
          for (var previous : voicePrevious.entrySet()) {
            Note continuation = currentHeads.get(previous.getKey());
            if (!previous.getValue().tieStart() || continuation == null || !continuation.tieStop())
              throw new IllegalArgumentException(
                  "Tie start must continue into the next same-pitch note in the same voice");
          }
          for (Note note : currentHeads.values())
            if (note.tieStop()) {
              Note previous = voicePrevious.get(note.writtenPitch());
              if (previous == null || !previous.tieStart())
                throw new IllegalArgumentException(
                    "Tie stop must continue the preceding same-pitch note in the same voice");
            }
          var eventTies = new LinkedHashMap<Pitch, Note>();
          for (Note note : currentHeads.values())
            if (note.tieStart()) eventTies.put(note.writtenPitch(), note);
          voicePrevious = Map.copyOf(eventTies);
        }
        if (!voicePrevious.isEmpty()) nextMeasureTies.put(voice.id(), voicePrevious);
      }
      previousTies = Map.copyOf(nextMeasureTies);
    }
    if (!previousTies.isEmpty())
      throw new IllegalArgumentException("Tie cannot continue beyond the end of a part");
  }

  public Part withMeasures(List<Measure> next) {
    return new Part(id, name, next, clefs, staves, additionalClefs);
  }

  public Part withIdentity(String nextId, String nextName) {
    return new Part(nextId, nextName, measures, clefs, staves, additionalClefs);
  }
}
