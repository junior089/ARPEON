package org.arpeon.desktop;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.instrument.InstrumentProfile;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;
import org.arpeon.domain.score.MusicalKey;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.score.ScoreDocument;
import org.arpeon.domain.score.ScoreEvent;
import org.arpeon.domain.score.ScoreTransform;
import org.arpeon.domain.time.Duration;
import org.arpeon.importer.DraftScoreBuilder;
import org.arpeon.importer.ImportDraft;
import org.arpeon.musicxml.MusicXmlExporter;
import org.arpeon.musicxml.MusicXmlImporter;
import org.arpeon.playback.MidiScorePlayer;
import org.arpeon.transport.ScoreTimeline;

/** In-memory revisions and local playback. The imported file remains owned by the user. */
final class ScoreWorkspace implements AutoCloseable {
  record MeasureView(int index, String number, long startTick, long durationTicks) {}

  record EventView(
      int index,
      String kind,
      String pitch,
      String step,
      int alter,
      int octave,
      long numerator,
      long denominator) {}

  record MeasureEvents(int index, String number, List<EventView> events) {}

  record PartView(String id, String name, List<MeasureEvents> measures) {}

  record InstrumentView(
      String id,
      String name,
      String low,
      String high,
      String comfortableLow,
      String comfortableHigh,
      int soundingSemitones,
      int midiProgram) {}

  record KeyView(String id, String label, boolean minor) {}

  record ScoreView(
      String title,
      String filename,
      String sha256,
      String parserVersion,
      String sourceKind,
      List<String> importWarnings,
      List<MeasureView> measures,
      List<PartView> parts,
      int revision,
      boolean canUndo,
      boolean canRedo,
      String keyId,
      List<ScoreTransform.RangeWarning> rangeWarnings,
      List<InstrumentView> instruments,
      List<KeyView> keys) {}

  record PitchChange(String partId, int measureIndex, int eventIndex, String from, String to) {}

  record PreviewView(
      String token,
      String description,
      int changedNotes,
      List<PitchChange> changes,
      List<ScoreTransform.RangeWarning> warningsBefore,
      List<ScoreTransform.RangeWarning> warningsAfter) {}

  record View(String xml, ScoreView score, MidiScorePlayer.State transport, PreviewView preview) {}

  private record Pending(int revision, ScoreDocument.State candidate, PreviewView view) {}

  private final MusicXmlImporter importer = new MusicXmlImporter();
  private final MusicXmlExporter exporter = new MusicXmlExporter();
  private MusicXmlImporter.ImportedScore imported;
  private ScoreDocument document;
  private ScoreTimeline timeline;
  private MidiScorePlayer player;
  private Pending pending;
  private String sourceKind = "MUSICXML";
  private List<String> importWarnings = List.of();

  synchronized View open(InputStream input, String filename) throws IOException {
    var next = importer.read(input, filename);
    return openImported(next, "MUSICXML", List.of());
  }

  synchronized View openDraft(ImportDraft draft, int fallbackOctave, int equalNoteTicks)
      throws IOException {
    var score = DraftScoreBuilder.build(draft, fallbackOctave, equalNoteTicks);
    var warnings = new ArrayList<>(draft.warnings());
    warnings.add("Partitura criada em 4/4; pausas finais completam o último compasso.");
    if (!draft.rhythm().equals("EXACT_TICKS"))
      warnings.add(
          "Duração igual de " + equalNoteTicks + " ticks escolhida na revisão; não veio da fonte.");
    if (draft.notes().stream().anyMatch(note -> note.octave() == null))
      warnings.add("Oitava " + fallbackOctave + " aplicada apenas às notas sem número.");
    return openImported(
        new MusicXmlImporter.ImportedScore(
            score, draft.filename(), draft.sha256(), draft.parserVersion(), null, Map.of()),
        draft.kind(),
        List.copyOf(warnings));
  }

  private View openImported(MusicXmlImporter.ImportedScore next, String kind, List<String> warnings)
      throws IOException {
    var candidate = new ScoreDocument(next.score(), next.keyId(), next.instruments());
    ScoreTimeline nextTimeline;
    MidiScorePlayer nextPlayer;
    try {
      exporter.write(
          candidate.current(),
          candidate.state().keyId(),
          profiles(candidate.state().instruments()));
      nextTimeline = new ScoreTimeline(candidate.current());
      nextPlayer = new MidiScorePlayer(nextTimeline, profiles(candidate.state().instruments()));
    } catch (IllegalArgumentException | ArithmeticException failure) {
      throw new IOException("Unsupported score timeline: " + failure.getMessage(), failure);
    }
    if (player != null) player.close();
    imported = next;
    sourceKind = kind;
    importWarnings = warnings;
    document = candidate;
    timeline = nextTimeline;
    player = nextPlayer;
    pending = null;
    return snapshot();
  }

  synchronized View snapshot() {
    if (player == null) throw new IllegalStateException("No score is open");
    var measures =
        timeline.measures().stream()
            .map(m -> new MeasureView(m.index(), m.number(), m.startTick(), m.durationTicks()))
            .toList();
    var partViews = new ArrayList<PartView>();
    var warnings = new ArrayList<ScoreTransform.RangeWarning>();
    for (var part : document.current().parts()) {
      var measureViews = new ArrayList<MeasureEvents>();
      for (int m = 0; m < part.measures().size(); m++) {
        var measure = part.measures().get(m);
        var events = new ArrayList<EventView>();
        for (int e = 0; e < measure.events().size(); e++) {
          ScoreEvent event = measure.events().get(e);
          Pitch pitch = event instanceof Note note ? note.writtenPitch() : null;
          events.add(
              new EventView(
                  e,
                  pitch == null ? "REST" : "NOTE",
                  pitch == null ? "" : pitch.label(),
                  pitch == null ? "" : pitch.pitchClass().name(),
                  pitch == null ? 0 : pitch.accidental().semitones(),
                  pitch == null ? 0 : pitch.octave(),
                  event.duration().numerator(),
                  event.duration().denominator()));
        }
        measureViews.add(new MeasureEvents(m, measure.number(), List.copyOf(events)));
      }
      partViews.add(new PartView(part.id(), part.name(), List.copyOf(measureViews)));
      String profileId = document.state().instruments().get(part.id());
      if (!profileId.equals("concert"))
        warnings.addAll(
            ScoreTransform.warnings(
                document.current(), part.id(), InstrumentCatalog.get(profileId)));
    }
    return new View(
        exporter.write(
            document.current(), document.state().keyId(), profiles(document.state().instruments())),
        new ScoreView(
            document.current().title(),
            imported.sourceFilename(),
            imported.sha256(),
            imported.parserVersion(),
            sourceKind,
            importWarnings,
            measures,
            List.copyOf(partViews),
            document.revision(),
            document.canUndo(),
            document.canRedo(),
            document.state().keyId(),
            List.copyOf(warnings),
            InstrumentCatalog.profiles().stream()
                .map(
                    profile ->
                        new InstrumentView(
                            profile.id(),
                            profile.displayName(),
                            profile.lowestWrittenPitch().label(),
                            profile.highestWrittenPitch().label(),
                            profile.lowestComfortablePitch().label(),
                            profile.highestComfortablePitch().label(),
                            profile.soundingTranspositionSemitones(),
                            profile.midiProgram()))
                .toList(),
            MusicalKey.choices().stream()
                .map(key -> new KeyView(key.id(), key.label(), key.minor()))
                .toList()),
        player.snapshot(),
        pending == null ? null : pending.view());
  }

  synchronized String exportXml() {
    if (document == null) throw new IllegalStateException("No score is open");
    return exporter.write(
        document.current(), document.state().keyId(), profiles(document.state().instruments()));
  }

  synchronized MidiScorePlayer.State transport() {
    if (player == null) throw new IllegalStateException("No score is open");
    return player.snapshot();
  }

  synchronized View edit(String action, JsonObject request) {
    if (document == null) throw new IllegalStateException("No score is open");
    if (action.equals("preview")) return preview(request);
    if (action.equals("apply-preview")) {
      keys(request, "token");
      if (pending == null
          || pending.revision() != document.revision()
          || !pending.view().token().equals(string(request, "token")))
        throw new IllegalArgumentException("Preview expired; review the transformation again");
      commit(pending.candidate());
      return snapshot();
    }
    if (action.equals("undo") || action.equals("redo")) {
      keys(request);
      if (action.equals("undo")) document.undo();
      else document.redo();
      rebuild();
      pending = null;
      return snapshot();
    }
    Score score = document.current();
    ScoreDocument.State state = document.state();
    ScoreDocument.State candidate;
    if (action.equals("set-instrument")) {
      keys(request, "partId", "instrumentId");
      String partId = part(request);
      if (score.parts().stream().noneMatch(item -> item.id().equals(partId)))
        throw new IllegalArgumentException("Unknown part");
      var instrument = InstrumentCatalog.get(string(request, "instrumentId"));
      var currentInstrument = InstrumentCatalog.get(state.instruments().get(partId));
      if (instrument.soundingTranspositionSemitones()
          != currentInstrument.soundingTranspositionSemitones())
        throw new IllegalArgumentException(
            "Use instrument adaptation preview when changing written/sounding transposition");
      var instruments = new LinkedHashMap<>(state.instruments());
      instruments.put(partId, instrument.id());
      candidate = new ScoreDocument.State(score, instruments, state.keyId());
    } else if (action.equals("set-pitch")) {
      keys(request, "partId", "measureIndex", "eventIndex", "step", "alter", "octave");
      ScoreEvent event = event(score, request);
      if (!(event instanceof Note)) throw new IllegalArgumentException("Select a note");
      candidate =
          scoreState(
              ScoreDocument.replaceEvent(
                  score,
                  part(request),
                  index(request, "measureIndex"),
                  index(request, "eventIndex"),
                  new Note(pitch(request), event.duration())));
    } else if (action.equals("set-duration")) {
      keys(request, "partId", "measureIndex", "eventIndex", "numerator", "denominator");
      candidate =
          scoreState(
              ScoreDocument.changeDuration(
                  score,
                  part(request),
                  index(request, "measureIndex"),
                  index(request, "eventIndex"),
                  duration(request)));
    } else if (action.equals("insert-note") || action.equals("insert-rest")) {
      if (action.equals("insert-note"))
        keys(
            request,
            "partId",
            "measureIndex",
            "eventIndex",
            "numerator",
            "denominator",
            "step",
            "alter",
            "octave");
      else keys(request, "partId", "measureIndex", "eventIndex", "numerator", "denominator");
      candidate =
          scoreState(
              ScoreDocument.insertIntoRest(
                  score,
                  part(request),
                  index(request, "measureIndex"),
                  index(request, "eventIndex"),
                  duration(request),
                  action.equals("insert-note") ? pitch(request) : null));
    } else if (action.equals("delete")) {
      keys(request, "partId", "measureIndex", "eventIndex");
      candidate =
          scoreState(
              ScoreDocument.deleteNote(
                  score,
                  part(request),
                  index(request, "measureIndex"),
                  index(request, "eventIndex")));
    } else throw new IllegalArgumentException("Unknown edit action");
    commit(candidate);
    return snapshot();
  }

  private View preview(JsonObject request) {
    if (request == null) throw new IllegalArgumentException("Preview parameters required");
    String kind = string(request, "kind");
    String partId = part(request);
    ScoreDocument.State state = document.state();
    Score score = state.score();
    var assigned = InstrumentCatalog.get(state.instruments().get(partId));
    ScoreDocument.State candidate;
    String description;
    String changedPartId = partId;
    if (kind.equals("interval") || kind.equals("octave")) {
      keys(request, "kind", "partId", "semitones");
      int semitones = integer(request, "semitones");
      if (kind.equals("octave") && Math.abs(semitones) != 12)
        throw new IllegalArgumentException("Choose one octave up or down");
      candidate = scoreState(ScoreTransform.transpose(score, partId, semitones, null));
      description =
          (kind.equals("octave") ? "Oitava" : "Intervalo") + ": " + semitones + " semitons";
    } else if (kind.equals("key")) {
      keys(request, "kind", "partId", "sourceKey", "targetKey");
      var source = MusicalKey.get(string(request, "sourceKey"));
      var target = MusicalKey.get(string(request, "targetKey"));
      candidate =
          new ScoreDocument.State(
              ScoreTransform.toKey(score, null, source, target), state.instruments(), target.id());
      changedPartId = null;
      description = "Partitura: " + source.label() + " → " + target.label();
    } else if (kind.equals("instrument")) {
      keys(request, "kind", "partId", "targetInstrument");
      var target = InstrumentCatalog.get(string(request, "targetInstrument"));
      Score sounding = ScoreTransform.convertPitch(score, partId, assigned, true);
      Score written = ScoreTransform.convertPitch(sounding, partId, target, false);
      var instruments = new LinkedHashMap<>(state.instruments());
      instruments.put(partId, target.id());
      candidate = new ScoreDocument.State(written, instruments, state.keyId());
      description = assigned.displayName() + " → " + target.displayName();
    } else if (kind.equals("sounding")) {
      keys(request, "kind", "partId");
      var instruments = new LinkedHashMap<>(state.instruments());
      instruments.put(partId, "concert");
      candidate =
          new ScoreDocument.State(
              ScoreTransform.convertPitch(score, partId, assigned, true),
              instruments,
              state.keyId());
      description = "Tom de concerto (som real)";
    } else throw new IllegalArgumentException("Unknown transformation");
    validate(candidate);
    var changes = changes(score, candidate.score(), changedPartId);
    var view =
        new PreviewView(
            UUID.randomUUID().toString(),
            description,
            changes.size(),
            changes.stream().limit(24).toList(),
            warnings(score, state.instruments()),
            warnings(candidate.score(), candidate.instruments()));
    pending = new Pending(document.revision(), candidate, view);
    return snapshot();
  }

  private static List<PitchChange> changes(Score before, Score after, String partId) {
    var result = new ArrayList<PitchChange>();
    for (int p = 0; p < before.parts().size(); p++) {
      var original = before.parts().get(p);
      if (partId != null && !original.id().equals(partId)) continue;
      var changed = after.parts().get(p);
      for (int m = 0; m < original.measures().size(); m++) {
        var first = original.measures().get(m).events();
        var second = changed.measures().get(m).events();
        for (int e = 0; e < first.size(); e++)
          if (first.get(e) instanceof Note oldNote
              && second.get(e) instanceof Note newNote
              && !oldNote.writtenPitch().equals(newNote.writtenPitch()))
            result.add(
                new PitchChange(
                    original.id(),
                    m,
                    e,
                    oldNote.writtenPitch().label(),
                    newNote.writtenPitch().label()));
      }
    }
    return List.copyOf(result);
  }

  private static List<ScoreTransform.RangeWarning> warnings(
      Score score, Map<String, String> assignments) {
    var result = new ArrayList<ScoreTransform.RangeWarning>();
    for (var part : score.parts()) {
      String profileId = assignments.get(part.id());
      if (!profileId.equals("concert"))
        result.addAll(ScoreTransform.warnings(score, part.id(), InstrumentCatalog.get(profileId)));
    }
    return List.copyOf(result);
  }

  private void commit(ScoreDocument.State candidate) {
    validate(candidate);
    if (candidate.equals(document.state())) {
      pending = null;
      return;
    }
    document.applyState(ignored -> candidate);
    rebuild();
    pending = null;
  }

  private void validate(ScoreDocument.State candidate) {
    exporter.write(candidate.score(), candidate.keyId(), profiles(candidate.instruments()));
    var checkedTimeline = new ScoreTimeline(candidate.score());
    try (var checkedPlayer =
        new MidiScorePlayer(checkedTimeline, profiles(candidate.instruments()))) {
      checkedPlayer.snapshot();
    }
  }

  private void rebuild() {
    MidiScorePlayer.State previous = player.snapshot();
    ScoreTimeline nextTimeline = new ScoreTimeline(document.current());
    MidiScorePlayer next =
        new MidiScorePlayer(nextTimeline, profiles(document.state().instruments()));
    next.setBpm(previous.startingBpm());
    next.setMetronome(previous.metronome());
    next.setCountIn(previous.countIn());
    next.selectPart(previous.activePartId());
    for (var part : previous.parts()) {
      next.setMute(part.id(), part.mute());
      next.setSolo(part.id(), part.solo());
    }
    next.seek(Math.min(previous.tick(), nextTimeline.totalTicks()));
    player.close();
    player = next;
    timeline = nextTimeline;
  }

  private static Map<String, InstrumentProfile> profiles(Map<String, String> assignments) {
    var result = new LinkedHashMap<String, InstrumentProfile>();
    assignments.forEach(
        (partId, profileId) -> result.put(partId, InstrumentCatalog.get(profileId)));
    return result;
  }

  private ScoreDocument.State scoreState(Score score) {
    return new ScoreDocument.State(score, document.state().instruments(), document.state().keyId());
  }

  private static ScoreEvent event(Score score, JsonObject request) {
    String partId = part(request);
    int m = index(request, "measureIndex");
    int e = index(request, "eventIndex");
    var selected =
        score.parts().stream()
            .filter(item -> item.id().equals(partId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown part"));
    if (m < 0
        || m >= selected.measures().size()
        || e < 0
        || e >= selected.measures().get(m).events().size())
      throw new IllegalArgumentException("Event outside score");
    return selected.measures().get(m).events().get(e);
  }

  private static String part(JsonObject request) {
    return string(request, "partId");
  }

  private static Duration duration(JsonObject request) {
    return new Duration(integer(request, "numerator"), integer(request, "denominator"));
  }

  private static Pitch pitch(JsonObject request) {
    return new Pitch(
        PitchClass.valueOf(string(request, "step")),
        Accidental.fromSemitones(integer(request, "alter")),
        integer(request, "octave"));
  }

  private static int index(JsonObject request, String name) {
    return integer(request, name);
  }

  private static int integer(JsonObject request, String name) {
    var element = request.get(name);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Expected number: " + name);
    return element.getAsBigDecimal().intValueExact();
  }

  private static String string(JsonObject request, String name) {
    var element = request.get(name);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected text: " + name);
    return element.getAsString();
  }

  private static void keys(JsonObject request, String... names) {
    if (request == null || !request.keySet().equals(Set.of(names)))
      throw new IllegalArgumentException("Unexpected edit parameters");
  }

  synchronized View command(String action, JsonObject request) {
    if (player == null) throw new IllegalStateException("No score is open");
    switch (action) {
      case "play" -> {
        exactTransportKeys(request);
        player.play();
      }
      case "pause" -> {
        exactTransportKeys(request);
        player.pause();
      }
      case "stop" -> {
        exactTransportKeys(request);
        player.stop();
      }
      case "seek" -> {
        exactTransportKeys(request, "tick");
        player.seek(request.get("tick").getAsBigDecimal().longValueExact());
      }
      case "bpm" -> {
        exactTransportKeys(request, "bpm");
        player.setBpm(request.get("bpm").getAsDouble());
      }
      case "metronome" -> {
        exactTransportKeys(request, "enabled");
        player.setMetronome(request.get("enabled").getAsBoolean());
      }
      case "count-in" -> {
        exactTransportKeys(request, "enabled");
        player.setCountIn(request.get("enabled").getAsBoolean());
      }
      case "select-part" -> {
        exactTransportKeys(request, "id");
        player.selectPart(request.get("id").getAsString());
      }
      case "mute" -> {
        exactTransportKeys(request, "id", "enabled");
        player.setMute(request.get("id").getAsString(), request.get("enabled").getAsBoolean());
      }
      case "solo" -> {
        exactTransportKeys(request, "id", "enabled");
        player.setSolo(request.get("id").getAsString(), request.get("enabled").getAsBoolean());
      }
      default -> throw new IllegalArgumentException("Unknown transport action");
    }
    return snapshot();
  }

  synchronized void stopIfPlaying() {
    if (player != null && player.snapshot().status().equals("PLAYING")) player.stop();
  }

  private static void exactTransportKeys(JsonObject object, String... names) {
    keys(object, names);
    for (String name : names) {
      var value = object.get(name);
      if (value == null
          || !value.isJsonPrimitive()
          || name.equals("enabled") && !value.getAsJsonPrimitive().isBoolean()
          || name.equals("id") && !value.getAsJsonPrimitive().isString()
          || (name.equals("tick") || name.equals("bpm")) && !value.getAsJsonPrimitive().isNumber())
        throw new IllegalArgumentException("Invalid transport parameter");
    }
  }

  @Override
  public synchronized void close() {
    if (player != null) player.close();
  }
}
