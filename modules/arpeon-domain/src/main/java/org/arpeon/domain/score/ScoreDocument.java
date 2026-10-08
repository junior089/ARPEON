package org.arpeon.domain.score;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.time.Duration;

/**
 * Immutable score revisions with bounded command history; the imported source is never modified.
 */
public final class ScoreDocument {
  public record State(Score score, Map<String, String> instruments, String keyId) {
    public State {
      instruments = Map.copyOf(instruments);
    }
  }

  private static final int HISTORY_LIMIT = 100;
  private final Score original;
  private final Deque<State> undo = new ArrayDeque<>();
  private final Deque<State> redo = new ArrayDeque<>();
  private State current;
  private int revision;

  public ScoreDocument(Score imported) {
    this(imported, null);
  }

  public ScoreDocument(Score imported, String keyId) {
    this(imported, keyId, Map.of());
  }

  public ScoreDocument(Score imported, String keyId, Map<String, String> instruments) {
    original = imported;
    if (keyId != null) MusicalKey.get(keyId);
    var assignments = new LinkedHashMap<String, String>();
    for (var part : imported.parts())
      assignments.put(part.id(), instruments.getOrDefault(part.id(), "concert"));
    current = new State(imported, assignments, keyId);
  }

  public Score original() {
    return original;
  }

  public Score current() {
    return current.score();
  }

  public State state() {
    return current;
  }

  public int revision() {
    return revision;
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }

  public void apply(UnaryOperator<Score> command) {
    applyState(
        state -> new State(command.apply(state.score()), state.instruments(), state.keyId()));
  }

  public void applyState(UnaryOperator<State> command) {
    State next = command.apply(current);
    if (next.equals(current)) return;
    if (undo.size() == HISTORY_LIMIT) undo.removeLast();
    undo.push(current);
    current = next;
    redo.clear();
    revision++;
  }

  public void undo() {
    if (undo.isEmpty()) throw new IllegalStateException("Nothing to undo");
    redo.push(current);
    current = undo.pop();
    revision++;
  }

  public void redo() {
    if (redo.isEmpty()) throw new IllegalStateException("Nothing to redo");
    undo.push(current);
    current = redo.pop();
    revision++;
  }

  public static Score replaceEvent(
      Score score, String partId, int measureIndex, int eventIndex, ScoreEvent replacement) {
    return changeMeasure(
        score,
        partId,
        measureIndex,
        events -> {
          events.set(checkedIndex(events, eventIndex), replacement);
          return events;
        });
  }

  public static Score deleteNote(Score score, String partId, int measureIndex, int eventIndex) {
    return changeMeasure(
        score,
        partId,
        measureIndex,
        events -> {
          ScoreEvent event = events.get(checkedIndex(events, eventIndex));
          if (!(event instanceof Note))
            throw new IllegalArgumentException("Select a note to remove");
          events.set(eventIndex, new Rest(event.duration()));
          return events;
        });
  }

  public static Score insertIntoRest(
      Score score,
      String partId,
      int measureIndex,
      int eventIndex,
      Duration duration,
      Pitch pitch) {
    return changeMeasure(
        score,
        partId,
        measureIndex,
        events -> {
          ScoreEvent event = events.get(checkedIndex(events, eventIndex));
          if (!(event instanceof Rest))
            throw new IllegalArgumentException("Select a rest to insert");
          if (duration.compareTo(event.duration()) > 0)
            throw new IllegalArgumentException("Insertion exceeds selected rest");
          events.set(eventIndex, pitch == null ? new Rest(duration) : new Note(pitch, duration));
          if (duration.compareTo(event.duration()) < 0)
            events.add(eventIndex + 1, new Rest(event.duration().minus(duration)));
          return events;
        });
  }

  public static Score changeDuration(
      Score score, String partId, int measureIndex, int eventIndex, Duration duration) {
    return changeMeasure(
        score,
        partId,
        measureIndex,
        events -> {
          ScoreEvent event = events.get(checkedIndex(events, eventIndex));
          int comparison = duration.compareTo(event.duration());
          if (comparison > 0) {
            Duration needed = duration.minus(event.duration());
            int following = eventIndex + 1;
            while (needed != null && following < events.size()) {
              ScoreEvent adjacent = events.get(following);
              if (!(adjacent instanceof Rest)) break;
              int restComparison = needed.compareTo(adjacent.duration());
              if (restComparison < 0) {
                events.set(following, new Rest(adjacent.duration().minus(needed)));
                needed = null;
              } else {
                events.remove(following);
                needed = restComparison == 0 ? null : needed.minus(adjacent.duration());
              }
            }
            if (needed != null)
              throw new IllegalArgumentException("Longer event needs adjacent rest time");
          } else if (comparison < 0) {
            events.add(eventIndex + 1, new Rest(event.duration().minus(duration)));
          }
          events.set(
              eventIndex,
              event instanceof Note note
                  ? new Note(note.writtenPitch(), duration)
                  : new Rest(duration));
          return events;
        });
  }

  public static Score mapNotes(Score score, String partId, UnaryOperator<Pitch> transform) {
    var parts = new ArrayList<Part>();
    boolean found = false;
    for (Part part : score.parts()) {
      if (partId != null && !part.id().equals(partId)) {
        parts.add(part);
        continue;
      }
      found = true;
      var measures = new ArrayList<Measure>();
      for (Measure measure : part.measures()) {
        var events = new ArrayList<ScoreEvent>();
        for (ScoreEvent event : measure.events())
          events.add(
              event instanceof Note note
                  ? new Note(transform.apply(note.writtenPitch()), note.duration())
                  : event);
        measures.add(
            new Measure(
                measure.number(),
                measure.timeSignature(),
                measure.tempo(),
                events,
                measure.pickup()));
      }
      parts.add(new Part(part.id(), part.name(), measures));
    }
    if (!found) throw new IllegalArgumentException("Unknown part");
    return new Score(score.title(), parts);
  }

  private static Score changeMeasure(
      Score score,
      String partId,
      int measureIndex,
      UnaryOperator<ArrayList<ScoreEvent>> transform) {
    var parts = new ArrayList<Part>();
    boolean found = false;
    for (Part part : score.parts()) {
      if (!part.id().equals(partId)) {
        parts.add(part);
        continue;
      }
      found = true;
      var measures = new ArrayList<>(part.measures());
      if (measureIndex < 0 || measureIndex >= measures.size())
        throw new IllegalArgumentException("Measure outside score");
      Measure measure = measures.get(measureIndex);
      List<ScoreEvent> events = transform.apply(new ArrayList<>(measure.events()));
      measures.set(
          measureIndex,
          new Measure(
              measure.number(),
              measure.timeSignature(),
              measure.tempo(),
              events,
              measure.pickup()));
      parts.add(new Part(part.id(), part.name(), measures));
    }
    if (!found) throw new IllegalArgumentException("Unknown part");
    return new Score(score.title(), parts);
  }

  private static int checkedIndex(List<ScoreEvent> events, int index) {
    if (index < 0 || index >= events.size())
      throw new IllegalArgumentException("Event outside measure");
    return index;
  }
}
