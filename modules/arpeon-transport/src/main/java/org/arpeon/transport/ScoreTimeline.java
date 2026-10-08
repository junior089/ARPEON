package org.arpeon.transport;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.TimeSignature;

/** Exact score positions in MIDI quarter-note ticks; elapsed seconds are converted explicitly. */
public final class ScoreTimeline {
  public static final int TICKS_PER_QUARTER = 480;

  public record MeasurePosition(
      int index, String number, long startTick, long durationTicks, TimeSignature meter) {}

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
    long tick = 0;
    double bpm = 120; // Explicit playback default when the source has no tempo.
    tempoPositions.add(new TempoPosition(0, bpm));
    for (int i = 0; i < first.size(); i++) {
      var reference = first.get(i);
      long length =
          durationTicks(
              reference.events().stream()
                  .map(e -> e.duration())
                  .reduce(Duration::plus)
                  .orElseThrow());
      for (var part : score.parts()) {
        if (part.measures().size() != first.size())
          throw new IllegalArgumentException("Parts have different measure counts");
        var measure = part.measures().get(i);
        long partLength =
            durationTicks(
                measure.events().stream()
                    .map(e -> e.duration())
                    .reduce(Duration::plus)
                    .orElseThrow());
        if (!measure.number().equals(reference.number())
            || !measure.timeSignature().equals(reference.timeSignature())
            || measure.pickup() != reference.pickup()
            || partLength != length)
          throw new IllegalArgumentException("Parts have incompatible measure timelines");
        if (measure.tempo().isPresent()) {
          double declared = measure.tempo().orElseThrow().quarterNotesPerMinute();
          for (var other : score.parts()) {
            var otherTempo = other.measures().get(i).tempo();
            if (otherTempo.isPresent()
                && otherTempo.orElseThrow().quarterNotesPerMinute() != declared)
              throw new IllegalArgumentException("Conflicting tempos at one measure");
          }
          if (declared != bpm) {
            bpm = declared;
            if (tick == 0) tempoPositions.set(0, new TempoPosition(0, bpm));
            else tempoPositions.add(new TempoPosition(tick, bpm));
          }
        }
      }
      positions.add(
          new MeasurePosition(i, reference.number(), tick, length, reference.timeSignature()));
      for (int partIndex = 0; partIndex < score.parts().size(); partIndex++) {
        long eventTick = tick;
        for (var event : score.parts().get(partIndex).measures().get(i).events()) {
          long eventLength = durationTicks(event.duration());
          if (event instanceof Note note)
            notePositions.add(
                new NotePosition(
                    partIndex, eventTick, eventLength, note.writtenPitch().midiNumber()));
          eventTick = Math.addExact(eventTick, eventLength);
        }
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
