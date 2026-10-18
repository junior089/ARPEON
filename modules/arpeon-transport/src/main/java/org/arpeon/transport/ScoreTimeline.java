package org.arpeon.transport;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.ScoreOffset;
import org.arpeon.domain.time.TimeSignature;

/** Exact score positions in MIDI quarter-note ticks; elapsed seconds are converted explicitly. */
public final class ScoreTimeline {
  public static final int TICKS_PER_QUARTER = 480;

  public record MeasurePosition(
      int index,
      String number,
      long startTick,
      long durationTicks,
      TimeSignature meter,
      int writtenIndex) {}

  public record NotePosition(int partIndex, long startTick, long durationTicks, int midiNumber) {}

  public record TempoPosition(long startTick, double bpm) {}

  private final Score score;
  private final List<MeasurePosition> measures;
  private final List<NotePosition> notes;
  private final List<TempoPosition> tempos;
  private final long totalTicks;

  public ScoreTimeline(Score score) {
    this.score = score;
    var first = score.parts().getFirst().measures();
    var positions = new ArrayList<MeasurePosition>();
    var notePositions = new ArrayList<NotePosition>();
    var tempoPositions = new ArrayList<TempoPosition>();
    var previousTieIndexes = new ArrayList<Map<String, Map<Pitch, Integer>>>();
    for (int i = 0; i < score.parts().size(); i++) previousTieIndexes.add(Map.of());
    var writtenTempos = new ArrayList<Double>(first.size());
    double writtenBpm = 120;
    for (int measureIndex = 0; measureIndex < first.size(); measureIndex++) {
      Double declared = null;
      for (var part : score.parts()) {
        if (part.measures().size() != first.size())
          throw new IllegalArgumentException("Parts have different measure counts");
        var tempo = part.measures().get(measureIndex).tempo();
        if (tempo.isPresent()) {
          double current = tempo.orElseThrow().quarterNotesPerMinute();
          if (declared != null && declared != current)
            throw new IllegalArgumentException("Conflicting tempos at one measure");
          declared = current;
        }
      }
      if (declared != null) writtenBpm = declared;
      writtenTempos.add(writtenBpm);
    }
    long tick = 0;
    double bpm = 120; // Explicit playback default when the source has no tempo.
    tempoPositions.add(new TempoPosition(0, bpm));
    List<ExecutionMap.Occurrence> execution = ExecutionMap.expand(score);
    int previousRepeatStart = -1;
    int previousPass = -1;
    for (int i = 0; i < execution.size(); i++) {
      var occurrence = execution.get(i);
      int writtenIndex = occurrence.measureIndex();
      if (occurrence.repeatStartMeasure() != previousRepeatStart
          || occurrence.pass() != previousPass) {
        if (occurrence.repeatStartMeasure() >= 0 && occurrence.pass() > 1)
          for (int partIndex = 0; partIndex < previousTieIndexes.size(); partIndex++)
            previousTieIndexes.set(partIndex, Map.of());
        previousRepeatStart = occurrence.repeatStartMeasure();
        previousPass = occurrence.pass();
      }
      var reference = first.get(writtenIndex);
      long length = measureLength(reference);
      for (var part : score.parts()) {
        var measure = part.measures().get(writtenIndex);
        long partLength = measureLength(measure);
        if (!measure.timeSignature().equals(reference.timeSignature())
            || measure.pickup() != reference.pickup()
            || partLength != length)
          throw new IllegalArgumentException("Parts have incompatible measure timelines");
      }
      double measureBpm = writtenTempos.get(writtenIndex);
      if (measureBpm != bpm) {
        bpm = measureBpm;
        if (tick == 0) tempoPositions.set(0, new TempoPosition(0, bpm));
        else tempoPositions.add(new TempoPosition(tick, bpm));
      }
      positions.add(
          new MeasurePosition(
              i, reference.number(), tick, length, reference.timeSignature(), writtenIndex));
      for (int partIndex = 0; partIndex < score.parts().size(); partIndex++) {
        var partTies = new LinkedHashMap<String, Map<Pitch, Integer>>();
        Map<String, Map<Pitch, Integer>> priorPartTies = previousTieIndexes.get(partIndex);
        for (var voice : score.parts().get(partIndex).measures().get(writtenIndex).voices()) {
          Map<Pitch, Integer> previousTies = priorPartTies.getOrDefault(voice.id(), Map.of());
          for (var timed : voice.events()) {
            var event = timed.event();
            long eventTick =
                Math.addExact(
                    tick, offsetTicks(timed.onset().numerator(), timed.onset().denominator()));
            long eventLength = durationTicks(event.duration());
            var heads = new LinkedHashMap<Pitch, Note>();
            for (Note note : event.noteHeads()) heads.put(note.writtenPitch(), note);
            for (var previous : previousTies.entrySet()) {
              Note continuation = heads.get(previous.getKey());
              if (continuation == null || !continuation.tieStop())
                throw new IllegalArgumentException("Invalid note tie in score timeline");
            }
            var nextTies = new LinkedHashMap<Pitch, Integer>();
            for (Note note : event.noteHeads()) {
              int midi = note.writtenPitch().midiNumber();
              Integer previousIndex = previousTies.get(note.writtenPitch());
              if (note.tieStop()) {
                if (previousIndex == null)
                  throw new IllegalArgumentException("Invalid note tie in score timeline");
                NotePosition previous = notePositions.get(previousIndex);
                notePositions.set(
                    previousIndex,
                    new NotePosition(
                        partIndex,
                        previous.startTick(),
                        Math.addExact(previous.durationTicks(), eventLength),
                        midi));
                if (note.tieStart()) nextTies.put(note.writtenPitch(), previousIndex);
              } else {
                notePositions.add(new NotePosition(partIndex, eventTick, eventLength, midi));
                if (note.tieStart()) nextTies.put(note.writtenPitch(), notePositions.size() - 1);
              }
            }
            previousTies = Map.copyOf(nextTies);
          }
          if (!previousTies.isEmpty()) partTies.put(voice.id(), previousTies);
        }
        previousTieIndexes.set(partIndex, Map.copyOf(partTies));
      }
      tick = Math.addExact(tick, length);
    }
    if (tick == 0) throw new IllegalArgumentException("Empty score timeline");
    this.measures = List.copyOf(positions);
    this.notes = List.copyOf(notePositions);
    this.tempos = List.copyOf(tempoPositions);
    this.totalTicks = tick;
  }

  private static long durationTicks(Duration duration) {
    var ticks =
        BigInteger.valueOf(duration.numerator())
            .multiply(BigInteger.valueOf(4L * TICKS_PER_QUARTER));
    var result = ticks.divideAndRemainder(BigInteger.valueOf(duration.denominator()));
    if (result[1].signum() != 0 || result[0].signum() <= 0)
      throw new IllegalArgumentException("Duration cannot be represented at 480 PPQ");
    return result[0].longValueExact();
  }

  private static long offsetTicks(long numerator, long denominator) {
    var ticks = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(4L * TICKS_PER_QUARTER));
    var result = ticks.divideAndRemainder(BigInteger.valueOf(denominator));
    if (result[1].signum() != 0)
      throw new IllegalArgumentException("Voice onset cannot be represented in MIDI ticks");
    return result[0].longValueExact();
  }

  private static long measureLength(org.arpeon.domain.score.Measure measure) {
    if (!measure.pickup()) return durationTicks(measure.timeSignature().measureDuration());
    var latest =
        measure.voices().stream()
            .map(voice -> voice.end())
            .max(ScoreOffset::compareTo)
            .orElseThrow();
    return offsetTicks(latest.numerator(), latest.denominator());
  }

  public Score score() {
    return score;
  }

  public List<MeasurePosition> measures() {
    return measures;
  }

  public List<NotePosition> notes() {
    return notes;
  }

  public List<TempoPosition> tempos() {
    return tempos;
  }

  public long totalTicks() {
    return totalTicks;
  }

  public double firstBpm() {
    return tempos.getFirst().bpm();
  }

  public int measureIndexAt(long tick) {
    if (tick < 0 || tick > totalTicks) throw new IllegalArgumentException("Tick outside score");
    if (tick == totalTicks) return measures.size() - 1;
    int low = 0, high = measures.size() - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      if (measures.get(middle).startTick() <= tick) low = middle + 1;
      else high = middle - 1;
    }
    return high;
  }

  public double bpmAt(long tick) {
    if (tick < 0 || tick > totalTicks) throw new IllegalArgumentException("Tick outside score");
    double value = tempos.getFirst().bpm();
    for (var tempo : tempos) {
      if (tempo.startTick() > tick) break;
      value = tempo.bpm();
    }
    return value;
  }

  public double secondsAt(long tick) {
    if (tick < 0 || tick > totalTicks) throw new IllegalArgumentException("Tick outside score");
    double seconds = 0;
    for (int i = 0; i < tempos.size(); i++) {
      var current = tempos.get(i);
      long end = i + 1 < tempos.size() ? tempos.get(i + 1).startTick() : totalTicks;
      if (tick <= current.startTick()) break;
      seconds +=
          (Math.min(tick, end) - current.startTick()) * 60.0 / (TICKS_PER_QUARTER * current.bpm());
      if (tick <= end) break;
    }
    return seconds;
  }

  public long tickAtSeconds(double seconds) {
    if (!Double.isFinite(seconds) || seconds < 0)
      throw new IllegalArgumentException("Invalid playback time");
    double remaining = seconds;
    for (int i = 0; i < tempos.size(); i++) {
      var current = tempos.get(i);
      long end = i + 1 < tempos.size() ? tempos.get(i + 1).startTick() : totalTicks;
      double length = (end - current.startTick()) * 60.0 / (TICKS_PER_QUARTER * current.bpm());
      if (remaining <= length) {
        long ticks = Math.round(remaining * TICKS_PER_QUARTER * current.bpm() / 60.0);
        return Math.min(totalTicks, current.startTick() + ticks);
      }
      remaining -= length;
    }
    return totalTicks;
  }

  public long countInTicks() {
    return durationTicks(measures.getFirst().meter().measureDuration());
  }
}
