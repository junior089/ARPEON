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
import org.arpeon.domain.time.ScoreOffset;

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
    return deleteNote(score, partId, measureIndex, eventIndex, "1", 0);
  }

  public static Score deleteNote(
      Score score, String partId, int measureIndex, int eventIndex, int headIndex) {
    return deleteNote(score, partId, measureIndex, eventIndex, "1", headIndex);
  }

  public static Score deleteNote(
      Score score, String partId, int measureIndex, int eventIndex, String voiceId, int headIndex) {
    return changeVoiceEvents(
        score,
        partId,
        measureIndex,
        voiceId,
        events -> {
          ScoreEvent event = events.get(checkedTimedIndex(events, eventIndex)).event();
          if (event instanceof Rest) throw new IllegalArgumentException("Select a note to remove");
          Note note = noteHead(event, headIndex);
          if (note.tieStart() || note.tieStop())
            throw new IllegalArgumentException("Remove the tie chain before deleting a tied note");
          TimedEvent timed = events.get(eventIndex);
          if (event instanceof Note)
            events.set(eventIndex, new TimedEvent(timed.onset(), new Rest(event.duration())));
          else {
            var heads = new ArrayList<>(((Chord) event).notes());
            heads.remove(headIndex);
            events.set(
                eventIndex,
                new TimedEvent(
                    timed.onset(), heads.size() == 1 ? heads.getFirst() : new Chord(heads)));
          }
          return events;
        });
  }

  public static Score removeTie(Score score, String partId, int measureIndex, int eventIndex) {
    return removeTie(score, partId, measureIndex, eventIndex, "1", 0);
  }

  public static Score removeTie(
      Score score, String partId, int measureIndex, int eventIndex, int headIndex) {
    return removeTie(score, partId, measureIndex, eventIndex, "1", headIndex);
  }

  public static Score removeTie(
      Score score, String partId, int measureIndex, int eventIndex, String voiceId, int headIndex) {
    var parts = new ArrayList<Part>();
    boolean found = false;
    for (Part part : score.parts()) {
      if (!part.id().equals(partId)) {
        parts.add(part);
        continue;
      }
      found = true;
      if (measureIndex < 0 || measureIndex >= part.measures().size())
        throw new IllegalArgumentException("Measure outside score");
      var measures = new ArrayList<>(part.measures());
      var selectedEvents = voice(measures.get(measureIndex), voiceId).events();
      ScoreEvent selectedEvent =
          selectedEvents.get(checkedTimedIndex(selectedEvents, eventIndex)).event();
      Note selected = noteHead(selectedEvent, headIndex);
      if (!selected.tieStart() && !selected.tieStop())
        throw new IllegalArgumentException("Select a tied note");
      Pitch selectedPitch = selected.writtenPitch();
      var noteAddresses = new ArrayList<int[]>();
      int selectedIndex = -1;
      for (int m = 0; m < measures.size(); m++) {
        var voiceEvents = voice(measures.get(m), voiceId).events();
        for (int e = 0; e < voiceEvents.size(); e++)
          for (int h = 0; h < voiceEvents.get(e).event().noteHeads().size(); h++)
            if (voiceEvents
                .get(e)
                .event()
                .noteHeads()
                .get(h)
                .writtenPitch()
                .equals(selectedPitch)) {
              if (m == measureIndex && e == eventIndex && h == headIndex)
                selectedIndex = noteAddresses.size();
              noteAddresses.add(new int[] {m, e, h});
            }
      }
      if (selectedIndex < 0) throw new IllegalArgumentException("Select a tied note");
      int first = selectedIndex;
      while (first > 0) {
        int[] current = noteAddresses.get(first);
        if (!noteAt(measures, voiceId, current).tieStop()) break;
        int[] previous = noteAddresses.get(first - 1);
        if (!noteAt(measures, voiceId, previous).tieStart())
          throw new IllegalArgumentException("Invalid tie chain");
        first--;
      }
      int last = selectedIndex;
      while (last + 1 < noteAddresses.size()) {
        int[] current = noteAddresses.get(last);
        if (!noteAt(measures, voiceId, current).tieStart()) break;
        int[] next = noteAddresses.get(last + 1);
        if (!noteAt(measures, voiceId, next).tieStop())
          throw new IllegalArgumentException("Invalid tie chain");
        last++;
      }
      for (int index = first; index <= last; index++) {
        int[] address = noteAddresses.get(index);
        Measure measure = measures.get(address[0]);
        measures.set(
            address[0],
            replaceVoiceEvent(
                measure,
                voiceId,
                address[1],
                event ->
                    replaceHead(
                        event,
                        address[2],
                        head -> new Note(head.writtenPitch(), head.duration()))));
      }
      parts.add(new Part(part.id(), part.name(), measures));
    }
    if (!found) throw new IllegalArgumentException("Unknown part");
    return new Score(score.title(), parts, score.repeatRegions(), score.endingRegions());
  }

  public static Score setPitch(
      Score score, String partId, int measureIndex, int eventIndex, Pitch pitch) {
    return setPitch(score, partId, measureIndex, eventIndex, "1", 0, pitch);
  }

  public static Score setPitch(
      Score score, String partId, int measureIndex, int eventIndex, int headIndex, Pitch pitch) {
    return setPitch(score, partId, measureIndex, eventIndex, "1", headIndex, pitch);
  }

  public static Score setPitch(
      Score score,
      String partId,
      int measureIndex,
      int eventIndex,
      String voiceId,
      int headIndex,
      Pitch pitch) {
    if (pitch == null) throw new IllegalArgumentException("Pitch required");
    Part selectedPart =
        score.parts().stream()
            .filter(part -> part.id().equals(partId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown part"));
    if (measureIndex < 0 || measureIndex >= selectedPart.measures().size())
      throw new IllegalArgumentException("Measure outside score");
    Voice selectedVoice = voice(selectedPart.measures().get(measureIndex), voiceId);
    if (eventIndex < 0 || eventIndex >= selectedVoice.events().size())
      throw new IllegalArgumentException("Event outside measure");
    Note selected = noteHead(selectedVoice.events().get(eventIndex).event(), headIndex);
    Pitch selectedPitch = selected.writtenPitch();
    var addresses = new ArrayList<int[]>();
    int selectedIndex = -1;
    for (int m = 0; m < selectedPart.measures().size(); m++) {
      var voiceEvents = voice(selectedPart.measures().get(m), voiceId).events();
      for (int e = 0; e < voiceEvents.size(); e++)
        for (int h = 0; h < voiceEvents.get(e).event().noteHeads().size(); h++)
          if (voiceEvents.get(e).event().noteHeads().get(h).writtenPitch().equals(selectedPitch)) {
            if (m == measureIndex && e == eventIndex && h == headIndex)
              selectedIndex = addresses.size();
            addresses.add(new int[] {m, e, h});
          }
    }
    if (selectedIndex < 0) throw new IllegalArgumentException("Select a note");
    int first = selectedIndex;
    int last = selectedIndex;
    if (selected.tieStart() || selected.tieStop()) {
      while (first > 0) {
        int[] current = addresses.get(first);
        if (!noteAt(selectedPart.measures(), voiceId, current).tieStop()) break;
        int[] previous = addresses.get(first - 1);
        if (!noteAt(selectedPart.measures(), voiceId, previous).tieStart())
          throw new IllegalArgumentException("Invalid tie chain");
        first--;
      }
      while (last + 1 < addresses.size()) {
        int[] current = addresses.get(last);
        if (!noteAt(selectedPart.measures(), voiceId, current).tieStart()) break;
        int[] next = addresses.get(last + 1);
        if (!noteAt(selectedPart.measures(), voiceId, next).tieStop())
          throw new IllegalArgumentException("Invalid tie chain");
        last++;
      }
    }
    var measures = new ArrayList<>(selectedPart.measures());
    for (int index = first; index <= last; index++) {
      int[] address = addresses.get(index);
      Measure measure = measures.get(address[0]);
      measures.set(
          address[0],
          replaceVoiceEvent(
              measure,
              voiceId,
              address[1],
              event ->
                  replaceHead(
                      event,
                      address[2],
                      note -> new Note(pitch, note.duration(), note.tieStart(), note.tieStop()))));
    }
    var parts = new ArrayList<Part>();
    for (Part part : score.parts())
      parts.add(part.id().equals(partId) ? new Part(part.id(), part.name(), measures) : part);
    return new Score(score.title(), parts, score.repeatRegions(), score.endingRegions());
  }

  public static Score insertIntoRest(
      Score score,
      String partId,
      int measureIndex,
      int eventIndex,
      Duration duration,
      Pitch pitch) {
    return insertIntoRest(score, partId, measureIndex, eventIndex, "1", duration, pitch);
  }

  public static Score insertIntoRest(
      Score score,
      String partId,
      int measureIndex,
      int eventIndex,
      String voiceId,
      Duration duration,
      Pitch pitch) {
    return changeVoiceEvents(
        score,
        partId,
        measureIndex,
        voiceId,
        events -> {
          TimedEvent timed = events.get(checkedTimedIndex(events, eventIndex));
          ScoreEvent event = timed.event();
          if (!(event instanceof Rest))
            throw new IllegalArgumentException("Select a rest to insert");
          if (duration.compareTo(event.duration()) > 0)
            throw new IllegalArgumentException("Insertion exceeds selected rest");
          events.set(
              eventIndex,
              new TimedEvent(
                  timed.onset(), pitch == null ? new Rest(duration) : new Note(pitch, duration)));
          if (duration.compareTo(event.duration()) < 0)
            events.add(
                eventIndex + 1,
                new TimedEvent(
                    timed.onset().plus(duration), new Rest(event.duration().minus(duration))));
          return events;
        });
  }

  public static Score changeDuration(
      Score score, String partId, int measureIndex, int eventIndex, Duration duration) {
    return changeDuration(score, partId, measureIndex, eventIndex, "1", duration);
  }

  public static Score changeDuration(
      Score score,
      String partId,
      int measureIndex,
      int eventIndex,
      String voiceId,
      Duration duration) {
    return changeVoiceEvents(
        score,
        partId,
        measureIndex,
        voiceId,
        events -> {
          TimedEvent selected = events.get(checkedTimedIndex(events, eventIndex));
          ScoreEvent event = selected.event();
          int comparison = duration.compareTo(event.duration());
          if (comparison > 0) {
            Duration needed = duration.minus(event.duration());
            int following = eventIndex + 1;
            ScoreOffset extensionCursor = selected.onset().plus(event.duration());
            while (needed != null && following < events.size()) {
              TimedEvent adjacentTimed = events.get(following);
              ScoreEvent adjacent = adjacentTimed.event();
              if (adjacentTimed.onset().compareTo(extensionCursor) > 0) {
                Duration gap = extensionCursor.distanceTo(adjacentTimed.onset());
                if (needed.compareTo(gap) <= 0) needed = null;
                else {
                  needed = needed.minus(gap);
                  extensionCursor = adjacentTimed.onset();
                }
              }
              if (needed == null) break;
              if (!(adjacent instanceof Rest) || !adjacentTimed.onset().equals(extensionCursor))
                break;
              int restComparison = needed.compareTo(adjacent.duration());
              if (restComparison < 0) {
                events.set(
                    following,
                    new TimedEvent(
                        adjacentTimed.onset().plus(needed),
                        new Rest(adjacent.duration().minus(needed))));
                needed = null;
              } else {
                events.remove(following);
                extensionCursor = extensionCursor.plus(adjacent.duration());
                needed = restComparison == 0 ? null : needed.minus(adjacent.duration());
              }
            }
            if (needed != null && following >= events.size()) needed = null;
            if (needed != null)
              throw new IllegalArgumentException("Longer event needs adjacent rest time");
          } else if (comparison < 0) {
            events.add(
                eventIndex + 1,
                new TimedEvent(
                    selected.onset().plus(duration), new Rest(event.duration().minus(duration))));
          }
          events.set(
              eventIndex,
              new TimedEvent(
                  selected.onset(),
                  event instanceof Rest
                      ? new Rest(duration)
                      : event.noteHeads().size() == 1
                          ? resized(event.noteHeads().getFirst(), duration)
                          : new Chord(
                              event.noteHeads().stream()
                                  .map(note -> resized(note, duration))
                                  .toList())));
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
        var voices = new ArrayList<Voice>();
        for (Voice voice : measure.voices()) {
          var timedEvents = new ArrayList<TimedEvent>();
          for (TimedEvent timed : voice.events()) {
            ScoreEvent event = timed.event();
            ScoreEvent mapped =
                event instanceof Chord chord
                    ? new Chord(
                        chord.notes().stream().map(note -> transformed(note, transform)).toList())
                    : event instanceof Note note ? transformed(note, transform) : event;
            timedEvents.add(new TimedEvent(timed.onset(), mapped));
          }
          voices.add(new Voice(voice.id(), timedEvents, voice.explicitlyTimed()));
        }
        measures.add(
            Measure.withVoices(
                measure.number(),
                measure.timeSignature(),
                measure.tempo(),
                voices,
                measure.pickup()));
      }
      parts.add(new Part(part.id(), part.name(), measures));
    }
    if (!found) throw new IllegalArgumentException("Unknown part");
    return new Score(score.title(), parts, score.repeatRegions(), score.endingRegions());
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
      legacyEvents(measures.get(measureIndex));
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
    return new Score(score.title(), parts, score.repeatRegions(), score.endingRegions());
  }

  private static Score changeVoiceEvents(
      Score score,
      String partId,
      int measureIndex,
      String voiceId,
      UnaryOperator<ArrayList<TimedEvent>> transform) {
    var parts = new ArrayList<Part>();
    boolean found = false;
    for (Part part : score.parts()) {
      if (!part.id().equals(partId)) {
        parts.add(part);
        continue;
      }
      found = true;
      if (measureIndex < 0 || measureIndex >= part.measures().size())
        throw new IllegalArgumentException("Measure outside score");
      var measures = new ArrayList<>(part.measures());
      Measure measure = measures.get(measureIndex);
      Voice selected = voice(measure, voiceId);
      var events = transform.apply(new ArrayList<>(selected.events()));
      var voices = new ArrayList<Voice>();
      for (Voice candidate : measure.voices())
        voices.add(
            candidate.id().equals(voiceId)
                ? new Voice(voiceId, events, candidate.explicitlyTimed())
                : candidate);
      measures.set(
          measureIndex,
          Measure.withVoices(
              measure.number(),
              measure.timeSignature(),
              measure.tempo(),
              voices,
              measure.pickup()));
      parts.add(new Part(part.id(), part.name(), measures));
    }
    if (!found) throw new IllegalArgumentException("Unknown part");
    return new Score(score.title(), parts, score.repeatRegions(), score.endingRegions());
  }

  private static int checkedIndex(List<ScoreEvent> events, int index) {
    if (index < 0 || index >= events.size())
      throw new IllegalArgumentException("Event outside measure");
    return index;
  }

  private static int checkedTimedIndex(List<TimedEvent> events, int index) {
    if (index < 0 || index >= events.size())
      throw new IllegalArgumentException("Event outside voice");
    return index;
  }

  private static List<ScoreEvent> legacyEvents(Measure measure) {
    if (measure.voices().size() != 1 || !measure.voices().getFirst().id().equals("1"))
      throw new IllegalArgumentException(
          "Select a voice explicitly before editing a multi-voice measure");
    return measure.events();
  }

  private static Voice voice(Measure measure, String voiceId) {
    return measure.voices().stream()
        .filter(candidate -> candidate.id().equals(voiceId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown voice"));
  }

  private static Measure replaceVoiceEvent(
      Measure measure, String voiceId, int eventIndex, UnaryOperator<ScoreEvent> transform) {
    var voices = new ArrayList<Voice>();
    boolean found = false;
    for (Voice candidate : measure.voices()) {
      if (!candidate.id().equals(voiceId)) {
        voices.add(candidate);
        continue;
      }
      found = true;
      if (eventIndex < 0 || eventIndex >= candidate.events().size())
        throw new IllegalArgumentException("Event outside voice");
      var events = new ArrayList<>(candidate.events());
      TimedEvent old = events.get(eventIndex);
      events.set(eventIndex, new TimedEvent(old.onset(), transform.apply(old.event())));
      voices.add(new Voice(candidate.id(), events, candidate.explicitlyTimed()));
    }
    if (!found) throw new IllegalArgumentException("Unknown voice");
    return Measure.withVoices(
        measure.number(), measure.timeSignature(), measure.tempo(), voices, measure.pickup());
  }

  private static Note resized(Note note, Duration duration) {
    return new Note(note.writtenPitch(), duration, note.tieStart(), note.tieStop());
  }

  private static Note transformed(Note note, UnaryOperator<Pitch> transform) {
    return new Note(
        transform.apply(note.writtenPitch()), note.duration(), note.tieStart(), note.tieStop());
  }

  private static Note noteAt(List<Measure> measures, int[] address) {
    return noteHead(measures.get(address[0]).events().get(address[1]), address[2]);
  }

  private static Note noteAt(List<Measure> measures, String voiceId, int[] address) {
    return noteHead(
        voice(measures.get(address[0]), voiceId).events().get(address[1]).event(), address[2]);
  }

  private static Note noteHead(ScoreEvent event, int headIndex) {
    if (headIndex < 0 || headIndex >= event.noteHeads().size())
      throw new IllegalArgumentException("Chord head outside event");
    return event.noteHeads().get(headIndex);
  }

  private static ScoreEvent replaceHead(
      ScoreEvent event, int headIndex, UnaryOperator<Note> replacement) {
    if (event instanceof Note) {
      if (headIndex != 0) throw new IllegalArgumentException("Chord head outside event");
      return replacement.apply((Note) event);
    }
    if (!(event instanceof Chord chord))
      throw new IllegalArgumentException("Select a note or chord head");
    var notes = new ArrayList<>(chord.notes());
    notes.set(headIndex, replacement.apply(notes.get(headIndex)));
    return new Chord(notes);
  }
}
