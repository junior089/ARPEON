package org.arpeon.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.score.ScoreEvent;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.TimeSignature;

/** Explicit 4/4 transcription choice. The draft remains available until accepted. */
public final class DraftScoreBuilder {
  private static final long MEASURE_TICKS = 1920;

  private DraftScoreBuilder() {}

  public static Score build(ImportDraft draft, int fallbackOctave, int equalNoteTicks) {
    if (fallbackOctave < 0 || fallbackOctave > 8)
      throw new IllegalArgumentException("Choose a fallback octave from 0 to 8");
    if (equalNoteTicks != 240 && equalNoteTicks != 480 && equalNoteTicks != 960)
      throw new IllegalArgumentException("Choose eighth, quarter or half note durations");
    boolean exact = draft.rhythm().equals("EXACT_TICKS");
    var measures = new ArrayList<Measure>();
    var events = new ArrayList<ScoreEvent>();
    long cursor = 0;
    long measureStart = 0;
    for (var observed : draft.notes()) {
      long onset = exact ? observed.onsetTicks() : cursor;
      long length = exact ? observed.durationTicks() : equalNoteTicks;
      if (onset < cursor || length < 1 || onset + length > 8192L * MEASURE_TICKS)
        throw new IllegalArgumentException("Overlapping or excessively long melody");
      while (onset >= measureStart + MEASURE_TICKS) {
        if (cursor < measureStart + MEASURE_TICKS)
          events.add(new Rest(duration(measureStart + MEASURE_TICKS - cursor)));
        measures.add(measure(measures.size() + 1, events));
        events = new ArrayList<>();
        measureStart += MEASURE_TICKS;
        cursor = measureStart;
      }
      if (onset + length > measureStart + MEASURE_TICKS)
        throw new IllegalArgumentException(
            "A MIDI note crosses a barline; tied notes are not supported yet");
      if (onset > cursor) events.add(new Rest(duration(onset - cursor)));
      int octave = observed.octave() == null ? fallbackOctave : observed.octave();
      events.add(
          new Note(new Pitch(observed.step(), observed.accidental(), octave), duration(length)));
      cursor = onset + length;
      if (cursor == measureStart + MEASURE_TICKS) {
        measures.add(measure(measures.size() + 1, events));
        events = new ArrayList<>();
        measureStart += MEASURE_TICKS;
      }
    }
    if (!events.isEmpty()) {
      if (cursor < measureStart + MEASURE_TICKS)
        events.add(new Rest(duration(measureStart + MEASURE_TICKS - cursor)));
      measures.add(measure(measures.size() + 1, events));
    }
    return new Score("Rascunho: " + draft.filename(), List.of(new Part("P1", "Melodia", measures)));
  }

  private static Duration duration(long ticks) {
    return new Duration(ticks, MEASURE_TICKS);
  }

  private static Measure measure(int number, List<ScoreEvent> events) {
    return new Measure(
        Integer.toString(number), new TimeSignature(4, 4), Optional.empty(), events, false);
  }
}
