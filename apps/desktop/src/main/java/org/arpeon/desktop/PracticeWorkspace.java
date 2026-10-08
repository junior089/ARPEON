package org.arpeon.desktop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.MusicalKey;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.score.ScoreEvent;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.TimeSignature;
import org.arpeon.musicxml.MusicXmlExporter;
import org.arpeon.playback.MidiScorePlayer;
import org.arpeon.transport.ScoreTimeline;
import org.arpeon.tuner.TunerObservation;

/** Small deterministic exercises built from the same score model and flute profile as imports. */
final class PracticeWorkspace implements AutoCloseable {
  record Target(String pitch, int midi, long startTick, long durationTicks) {}

  record Feedback(
      String state,
      String target,
      String detected,
      Double cents,
      double confidence,
      Long responseMs,
      Double heldMs,
      Double stabilityCents,
      int interruptions,
      Long timingErrorMs) {}

  record View(
      String kind,
      String scaleType,
      String keyId,
      int octave,
      int bpm,
      long longToneTargetMs,
      int iteration,
      boolean loop,
      int progressiveStep,
      String fingeringDatasetId,
      String xml,
      List<Target> targets,
      int targetIndex,
      boolean active,
      boolean attemptOpen,
      Feedback feedback,
      MidiScorePlayer.State transport) {}

  record Summary(
      String id,
      String kind,
      String keyId,
      String startedAt,
      String endedAt,
      boolean completed,
      int targets,
      int reached,
      double heldMs,
      Double stabilityCents,
      int interruptions,
      int bpm) {}

  private final MusicXmlExporter exporter = new MusicXmlExporter();
  private MidiScorePlayer player;
  private String xml;
  private String kind;
  private String scaleType;
  private MusicalKey key;
  private int octave;
  private int bpm;
  private int attemptBpm;
  private int iteration;
  private boolean loop;
  private int progressiveStep;
  private List<Target> targets = List.of();
  private int targetIndex;
  private boolean active;
  private long startedNanos;
  private Instant startedAt;
  private String attemptId;
  private long targetStartedNanos;
  private long previousCapturedAt;
  private long previousTrackingAt;
  private double heldMs;
  private double centsSquaredSum;
  private int centsSamples;
  private int interruptions;
  private Feedback feedback = new Feedback("READY", "", null, null, 0, null, null, null, 0, null);

  synchronized View create(
      String requestedKind,
      String requestedKey,
      int requestedOctave,
      String requestedScale,
      int requestedBpm,
      boolean requestedLoop,
      int requestedStep) {
    if (attemptId != null)
      throw new IllegalStateException(
          "Finish the current attempt before creating another exercise");
    if (!List.of("NOTE", "LONG_TONE", "SCALE", "ARPEGGIO", "RHYTHM").contains(requestedKind))
      throw new IllegalArgumentException("Unknown exercise kind");
    if (requestedOctave < 4 || requestedOctave > 5)
      throw new IllegalArgumentException("Choose octave 4 or 5");
    if (requestedBpm < 40 || requestedBpm > 180 || requestedStep < 0 || requestedStep > 20)
      throw new IllegalArgumentException("Practice tempo outside supported range");
    if (!List.of("MAJOR", "NATURAL_MINOR", "HARMONIC_MINOR", "MELODIC_MINOR", "CHROMATIC")
        .contains(requestedScale)) throw new IllegalArgumentException("Unknown scale type");
    MusicalKey chosenKey = MusicalKey.get(requestedKey);
    if ((requestedScale.equals("MAJOR") && chosenKey.minor())
        || (requestedScale.contains("MINOR") && !chosenKey.minor()))
      throw new IllegalArgumentException("Scale mode and key must agree");
    Score generated = generate(requestedKind, chosenKey, requestedOctave, requestedScale);
    var generatedTimeline = new ScoreTimeline(generated);
    var generatedPlayer =
        new MidiScorePlayer(generatedTimeline, Map.of("practice", InstrumentCatalog.get("flute")));
    generatedPlayer.setBpm(requestedBpm);
    generatedPlayer.setMetronome(true);
    generatedPlayer.setCountIn(true);
    String generatedXml =
        exporter.write(
            generated, chosenKey.id(), Map.of("practice", InstrumentCatalog.get("flute")));
    if (player != null) player.close();
    player = generatedPlayer;
    xml = generatedXml;
    kind = requestedKind;
    scaleType = requestedScale;
    key = chosenKey;
    octave = requestedOctave;
    bpm = requestedBpm;
    attemptBpm = requestedBpm;
    iteration = 1;
    loop = requestedLoop;
    progressiveStep = requestedStep;
    var planned = new ArrayList<Target>();
    for (var note : generatedTimeline.notes())
      planned.add(
          new Target(
              Pitch.fromMidiNumber(note.midiNumber()).label(),
              note.midiNumber(),
              note.startTick(),
              note.durationTicks()));
    targets = List.copyOf(planned);
    resetAttempt();
    return snapshot();
  }

  private static Score generate(String kind, MusicalKey key, int octave, String scaleType) {
    String tonicName = key.id().substring(0, key.id().indexOf('_'));
    Pitch tonicPitch =
        new Pitch(
            PitchClass.valueOf(tonicName.substring(0, 1)),
            Accidental.fromSemitones(
                tonicName.endsWith("#") ? 1 : tonicName.endsWith("b") ? -1 : 0),
            octave);
    int tonic = tonicPitch.midiNumber();
    int[] intervals =
        switch (kind) {
          case "NOTE", "LONG_TONE" -> new int[] {0};
          case "ARPEGGIO" ->
              key.minor() ? new int[] {0, 3, 7, 12, 7, 3, 0} : new int[] {0, 4, 7, 12, 7, 4, 0};
          case "RHYTHM" -> new int[] {0, 0, 0, 0, 0};
          case "SCALE" -> scaleIntervals(scaleType);
          default -> throw new IllegalArgumentException("Unknown exercise kind");
        };
    Duration[] rhythm =
        kind.equals("RHYTHM")
            ? new Duration[] {
              new Duration(1, 4),
              new Duration(1, 8),
              new Duration(1, 8),
              new Duration(1, 4),
              new Duration(1, 4)
            }
            : null;
    var measures = new ArrayList<Measure>();
    var events = new ArrayList<ScoreEvent>();
    Duration filled = null;
    int number = 1;
    for (int i = 0; i < intervals.length; i++) {
      int midi = tonic + intervals[i];
      Pitch pitch = key.spell(midi);
      if (!InstrumentCatalog.get("flute").inRange(pitch))
        throw new IllegalArgumentException(
            "Exercise leaves the flute range; choose another key/octave");
      Duration duration =
          kind.equals("LONG_TONE")
              ? new Duration(1, 1)
              : rhythm == null ? new Duration(1, 4) : rhythm[i];
      events.add(new Note(pitch, duration));
      filled = filled == null ? duration : filled.plus(duration);
      if (filled.compareTo(new Duration(1, 1)) == 0) {
        measures.add(
            new Measure(
                Integer.toString(number++),
                new TimeSignature(4, 4),
                java.util.Optional.empty(),
                events,
                false));
        events = new ArrayList<>();
        filled = null;
      }
    }
    if (filled != null) {
      events.add(new Rest(new Duration(1, 1).minus(filled)));
      measures.add(
          new Measure(
              Integer.toString(number),
              new TimeSignature(4, 4),
              java.util.Optional.empty(),
              events,
              false));
    }
    return new Score("Exercício: " + kind, List.of(new Part("practice", "Flauta", measures)));
  }

  private static int[] scaleIntervals(String type) {
    int[] rising =
        switch (type) {
          case "MAJOR" -> new int[] {0, 2, 4, 5, 7, 9, 11, 12};
          case "NATURAL_MINOR" -> new int[] {0, 2, 3, 5, 7, 8, 10, 12};
          case "HARMONIC_MINOR" -> new int[] {0, 2, 3, 5, 7, 8, 11, 12};
          case "MELODIC_MINOR" -> new int[] {0, 2, 3, 5, 7, 9, 11, 12};
          case "CHROMATIC" -> new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
          default -> throw new IllegalArgumentException("Unknown scale type");
        };
    int[] descending =
        type.equals("MELODIC_MINOR")
            ? new int[] {10, 8, 7, 5, 3, 2, 0}
            : java.util.stream.IntStream.range(0, rising.length - 1)
                .map(i -> rising[rising.length - 2 - i])
                .toArray();
    int[] result = java.util.Arrays.copyOf(rising, rising.length + descending.length);
    System.arraycopy(descending, 0, result, rising.length, descending.length);
    return result;
  }

  synchronized View start() {
    requireExercise();
    if (attemptId != null)
      throw new IllegalStateException("Finish the current attempt before starting another");
    resetAttempt();
    player.stop();
    active = true;
    startedNanos = System.nanoTime();
    attemptBpm = bpm;
    startedAt = Instant.now();
    attemptId = UUID.randomUUID().toString();
    targetStartedNanos = startedNanos;
    feedback =
        new Feedback(
            "LISTENING", targets.getFirst().pitch(), null, null, 0, null, null, null, 0, null);
    return snapshot();
  }

  synchronized View observe(TunerObservation observation) {
    requireExercise();
    if (!active || observation.capturedAt() == null) return snapshot();
    long captured = observation.capturedAt().toEpochMilli();
    if (captured < startedAt.toEpochMilli() || captured <= previousCapturedAt) return snapshot();
    previousCapturedAt = captured;
    Target target = targets.get(targetIndex);
    if (observation.state() != TunerObservation.State.TRACKING) {
      if (previousTrackingAt > 0) {
        interruptions++;
        previousTrackingAt = 0;
      }
      feedback =
          new Feedback(
              observation.state().name(),
              target.pitch(),
              null,
              null,
              observation.confidence(),
              null,
              kind.equals("LONG_TONE") ? heldMs : null,
              stability(),
              interruptions,
              null);
      return snapshot();
    }
    double cents =
        1200
            * Math.log(
                observation.frequencyHz()
                    / Pitch.fromMidiNumber(target.midi()).frequencyHz(observation.referenceHz()))
            / Math.log(2);
    boolean correct = Math.abs(cents) <= 50 && observation.confidence() >= 0.65;
    long response = Math.max(0, (System.nanoTime() - targetStartedNanos) / 1_000_000);
    if (kind.equals("LONG_TONE")) {
      if (correct) {
        if (previousTrackingAt > 0 && captured - previousTrackingAt <= 350)
          heldMs += Math.max(0, Math.min(350, captured - previousTrackingAt));
        previousTrackingAt = captured;
        centsSquaredSum += cents * cents;
        centsSamples++;
      } else if (previousTrackingAt > 0) {
        interruptions++;
        previousTrackingAt = 0;
      }
      if (heldMs >= longToneTargetMs()) active = false;
    } else if (correct && !kind.equals("RHYTHM")) {
      targetIndex++;
      if (targetIndex == targets.size()) active = false;
      else targetStartedNanos = System.nanoTime();
    }
    feedback =
        new Feedback(
            correct ? (active ? "CORRECT" : "COMPLETE") : "INCORRECT",
            target.pitch(),
            observation.nearestPitch(),
            cents,
            observation.confidence(),
            response,
            kind.equals("LONG_TONE") ? heldMs : null,
            stability(),
            interruptions,
            null);
    return snapshot();
  }

  synchronized View tap() {
    requireExercise();
    if (!active || !kind.equals("RHYTHM"))
      throw new IllegalStateException("Start a rhythm exercise before tapping");
    Target target = targets.get(targetIndex);
    long elapsed = (System.nanoTime() - startedNanos) / 1_000_000;
    long expected = Math.round(target.startTick() * 60_000.0 / (480 * attemptBpm));
    long error = elapsed - expected;
    targetIndex++;
    if (targetIndex == targets.size()) active = false;
    feedback =
        new Feedback(
            Math.abs(error) <= 120 ? "ON_TIME" : error < 0 ? "EARLY" : "LATE",
            target.pitch(),
            null,
            null,
            0,
            elapsed,
            null,
            null,
            0,
            error);
    return snapshot();
  }

  synchronized View playback(String action) {
    requireExercise();
    switch (action) {
      case "play" -> player.play();
      case "pause" -> player.pause();
      case "stop" -> player.stop();
      default -> throw new IllegalArgumentException("Unknown practice playback action");
    }
    return snapshot();
  }

  synchronized Summary finish() {
    requireExercise();
    if (attemptId == null) throw new IllegalStateException("Start an attempt before saving it");
    active = false;
    player.stop();
    Summary summary =
        new Summary(
            attemptId,
            kind,
            key.id(),
            startedAt.toString(),
            Instant.now().toString(),
            targetIndex == targets.size()
                || kind.equals("LONG_TONE") && heldMs >= longToneTargetMs(),
            targets.size(),
            kind.equals("LONG_TONE") && heldMs >= longToneTargetMs() ? 1 : targetIndex,
            heldMs,
            stability(),
            interruptions,
            attemptBpm);
    return summary;
  }

  synchronized void markSaved() {
    if (attemptId == null) throw new IllegalStateException("No attempt to mark as saved");
    attemptId = null;
  }

  synchronized View snapshot() {
    requireExercise();
    var state = player.snapshot();
    if (loop && state.status().equals("STOPPED") && state.tick() == state.totalTicks()) {
      iteration++;
      bpm = Math.min(180, bpm + progressiveStep);
      player.setBpm(bpm);
      player.seek(0);
      player.play();
      state = player.snapshot();
    }
    return new View(
        kind,
        scaleType,
        key.id(),
        octave,
        bpm,
        kind.equals("LONG_TONE") ? longToneTargetMs() : 0,
        iteration,
        loop,
        progressiveStep,
        InstrumentCatalog.get("flute").fingeringDatasetId(),
        xml,
        targets,
        targetIndex,
        active,
        attemptId != null,
        feedback,
        state);
  }

  synchronized void stopIfPlaying() {
    if (player != null && player.snapshot().status().equals("PLAYING")) player.stop();
  }

  private Double stability() {
    return centsSamples == 0 ? null : Math.sqrt(centsSquaredSum / centsSamples);
  }

  private long longToneTargetMs() {
    return Math.round(targets.getFirst().durationTicks() * 60_000.0 / (480 * attemptBpm));
  }

  private void resetAttempt() {
    active = false;
    attemptId = null;
    targetIndex = 0;
    previousCapturedAt = 0;
    previousTrackingAt = 0;
    heldMs = 0;
    centsSquaredSum = 0;
    centsSamples = 0;
    interruptions = 0;
    feedback =
        new Feedback("READY", targets.getFirst().pitch(), null, null, 0, null, null, null, 0, null);
  }

  private void requireExercise() {
    if (player == null) throw new IllegalStateException("Create an exercise first");
  }

  @Override
  public synchronized void close() {
    if (player != null) player.close();
  }
}
