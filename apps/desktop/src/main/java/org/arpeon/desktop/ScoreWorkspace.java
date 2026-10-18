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
import org.arpeon.analysis.ExpectedEvent;
import org.arpeon.analysis.PerformanceAnalyzer;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.instrument.InstrumentProfile;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;
import org.arpeon.domain.score.Chord;
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
import org.arpeon.omr.OmrResult;
import org.arpeon.playback.MidiScorePlayer;
import org.arpeon.transport.ScoreTimeline;

/** In-memory revisions and local playback. The imported file remains owned by the user. */
final class ScoreWorkspace implements AutoCloseable {
  record MeasureView(
      int index, String number, long startTick, long durationTicks, int writtenIndex) {}

  record EventView(
      int index,
      String voiceId,
      int eventIndex,
      long onsetNumerator,
      long onsetDenominator,
      String kind,
      String pitch,
      String step,
      int alter,
      int octave,
      long numerator,
      long denominator,
      boolean tieStart,
      boolean tieStop,
      List<HeadView> heads) {}

  record HeadView(int index, String pitch, boolean tieStart, boolean tieStop) {}

  record MeasureEvents(int index, String number, List<EventView> events) {}

  record MeasureEventsPage(
      int revision,
      String partId,
      int index,
      String number,
      List<EventView> events,
      long durationNumerator,
      long durationDenominator) {}

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
      String composer,
      String filename,
      String sha256,
      String parserVersion,
      String sourceKind,
      List<String> importWarnings,
      ImportWorkspace.Source source,
      List<MeasureView> measures,
      List<PartView> parts,
      int revision,
      boolean canUndo,
      boolean canRedo,
      String keyId,
      List<ScoreTransform.RangeWarning> rangeWarnings,
      List<InstrumentView> instruments,
      List<KeyView> keys) {}

  record PitchChange(
      String partId, int measureIndex, String voiceId, int eventIndex, String from, String to) {}

  private record ChangeSet(List<PitchChange> events, int changedHeads) {}

  record PreviewView(
      String token,
      String description,
      int changedNotes,
      List<PitchChange> changes,
      List<ScoreTransform.RangeWarning> warningsBefore,
      List<ScoreTransform.RangeWarning> warningsAfter,
      String xml) {}

  private record PositionedEvent(
      String voiceId, int eventIndex, org.arpeon.domain.score.TimedEvent timed) {}

  record View(
      String xml,
      ScoreView score,
      MidiScorePlayer.State transport,
      PreviewView preview,
      RehearsalState rehearsal) {}

  record ImportPartView(
      String id,
      String name,
      String instrumentId,
      int measures,
      boolean compatible,
      String problem) {}

  record PartImportView(String token, String filename, List<ImportPartView> parts) {}

  private record PartImport(String token, int revision, MusicXmlImporter.ImportedScore source) {}

  record AnalysisSource(
      String title,
      String filename,
      String sha256,
      int revision,
      String partId,
      String partName,
      int soundingSemitones,
      double durationMs,
      double initialBpm,
      List<ExpectedEvent> expected,
      List<Long> expectedTicks,
      List<AnalysisStudy.Location> locations,
      String scoreXmlSha256) {}

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
  private ImportWorkspace.Source source;
  private String viewMode = "full";
  private final Set<String> hiddenParts = new java.util.LinkedHashSet<>();
  private final Map<String, RehearsalState.PartOrigin> partOrigins = new LinkedHashMap<>();
  private PartImport partImport;
  private int preferenceRevision;

  synchronized View open(InputStream input, String filename) throws IOException {
    var next = importer.read(input, filename);
    return openImported(next, "MUSICXML", List.of(), null);
  }

  synchronized View open(byte[] bytes, String filename) throws IOException {
    var next = importer.read(bytes, filename);
    return openImported(next, "MUSICXML", List.of(), null);
  }

  synchronized View openMxl(byte[] bytes, String filename) throws IOException {
    var next = importer.readMxl(bytes, filename);
    return openImported(next, "MUSICXML", List.of(), null);
  }

  synchronized View openOmr(OmrResult result) throws IOException {
    var recognized = result.imported();
    var source =
        new MusicXmlImporter.ImportedScore(
            recognized.score(),
            result.sourceFilename(),
            result.sourceSha256(),
            "audiveris+" + recognized.parserVersion(),
            recognized.keyId(),
            recognized.instruments());
    var warnings = new ArrayList<>(result.warnings());
    warnings.add(
        "Origem OMR: "
            + result.pages()
            + " página(s); SHA-256 do original: "
            + result.sourceSha256());
    warnings.add("Páginas a revisar: " + result.pagesRequiringReview());
    return openImported(source, "OMR", List.copyOf(warnings), null);
  }

  synchronized View openDraft(ImportDraft draft, int fallbackOctave, int equalNoteTicks)
      throws IOException {
    return openDraft(draft, null, fallbackOctave, equalNoteTicks);
  }

  synchronized View openDraft(
      ImportDraft draft, ImportWorkspace.Source origin, int fallbackOctave, int equalNoteTicks)
      throws IOException {
    var score = DraftScoreBuilder.build(draft, fallbackOctave, equalNoteTicks);
    var warnings = new ArrayList<>(draft.warnings());
    warnings.add("Partitura criada em 4/4; pausas finais completam o último compasso.");
    if (!draft.rhythm().equals("EXACT_TICKS"))
      warnings.add(
          "Duração igual de " + equalNoteTicks + " ticks escolhida na revisão; não veio da fonte.");
    if (draft.notes().stream().anyMatch(note -> note.octave() == null))
      warnings.add("Oitava " + fallbackOctave + " aplicada apenas às notas sem número.");
    if (origin != null) {
      warnings.add(
          "Fonte Wikimedia Commons: confira autoria e licença na página original antes de"
              + " redistribuir.");
      origin.notes().forEach(step -> warnings.add("Processamento: " + step));
    }
    return openImported(
        new MusicXmlImporter.ImportedScore(
            score, draft.filename(), draft.sha256(), draft.parserVersion(), null, Map.of()),
        draft.kind(),
        List.copyOf(warnings),
        origin);
  }

  private View openImported(
      MusicXmlImporter.ImportedScore next,
      String kind,
      List<String> warnings,
      ImportWorkspace.Source origin)
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
    source = origin;
    document = candidate;
    timeline = nextTimeline;
    player = nextPlayer;
    pending = null;
    partImport = null;
    preferenceRevision = 0;
    viewMode = "full";
    hiddenParts.clear();
    partOrigins.clear();
    return snapshot();
  }

  private RehearsalState rehearsal() {
    return new RehearsalState(player.settings(), viewMode, hiddenParts, partOrigins);
  }

  synchronized View snapshot() {
    if (player == null) throw new IllegalStateException("No score is open");
    var measures =
        timeline.measures().stream()
            .map(
                m ->
                    new MeasureView(
                        m.index(), m.number(), m.startTick(), m.durationTicks(), m.writtenIndex()))
            .toList();
    var partViews = new ArrayList<PartView>();
    var warnings = new ArrayList<ScoreTransform.RangeWarning>();
    for (var part : document.current().parts()) {
      var measureViews = new ArrayList<MeasureEvents>();
      for (int m = 0; m < part.measures().size(); m++) {
        var measure = part.measures().get(m);
        measureViews.add(new MeasureEvents(m, measure.number(), List.of()));
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
            document.current().composer(),
            imported.sourceFilename(),
            imported.sha256(),
            imported.parserVersion(),
            sourceKind,
            importWarnings,
            source,
            measures,
            List.copyOf(partViews),
            document.revision(),
            document.canUndo(),
            document.canRedo(),
            document.state().keyId(),
            List.copyOf(warnings),
            instrumentChoices().stream()
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
        pending == null ? null : pending.view(),
        rehearsal());
  }

  synchronized MeasureEventsPage measureEvents(String partId, int measureIndex) {
    if (document == null) throw new IllegalStateException("No score is open");
    var part =
        document.current().parts().stream()
            .filter(candidate -> candidate.id().equals(partId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown score part"));
    if (measureIndex < 0 || measureIndex >= part.measures().size())
      throw new IllegalArgumentException("Unknown measure index");
    var measure = part.measures().get(measureIndex);
    var positionedEvents = new ArrayList<PositionedEvent>();
    for (var voice : measure.voices())
      for (int eventIndex = 0; eventIndex < voice.events().size(); eventIndex++)
        positionedEvents.add(
            new PositionedEvent(voice.id(), eventIndex, voice.events().get(eventIndex)));
    positionedEvents.sort(
        java.util.Comparator.comparing((PositionedEvent event) -> event.timed().onset())
            .thenComparing(PositionedEvent::voiceId));
    var events = new ArrayList<EventView>(positionedEvents.size());
    for (int index = 0; index < positionedEvents.size(); index++) {
      var positioned = positionedEvents.get(index);
      var timed = positioned.timed();
      ScoreEvent event = timed.event();
      var heads = event.noteHeads();
      var headViews = new ArrayList<HeadView>(heads.size());
      for (int headIndex = 0; headIndex < heads.size(); headIndex++) {
        Note head = heads.get(headIndex);
        headViews.add(
            new HeadView(headIndex, head.writtenPitch().label(), head.tieStart(), head.tieStop()));
      }
      Pitch pitch = heads.isEmpty() ? null : heads.getFirst().writtenPitch();
      events.add(
          new EventView(
              index,
              positioned.voiceId(),
              positioned.eventIndex(),
              timed.onset().numerator(),
              timed.onset().denominator(),
              event instanceof Chord ? "CHORD" : pitch == null ? "REST" : "NOTE",
              event instanceof Chord
                  ? heads.stream()
                      .map(head -> head.writtenPitch().label())
                      .collect(java.util.stream.Collectors.joining(" + "))
                  : pitch == null ? "" : pitch.label(),
              pitch == null ? "" : pitch.pitchClass().name(),
              pitch == null ? 0 : pitch.accidental().semitones(),
              pitch == null ? 0 : pitch.octave(),
              event.duration().numerator(),
              event.duration().denominator(),
              heads.stream().anyMatch(Note::tieStart),
              heads.stream().anyMatch(Note::tieStop),
              List.copyOf(headViews)));
    }
    return new MeasureEventsPage(
        document.revision(),
        partId,
        measureIndex,
        measure.number(),
        List.copyOf(events),
        measure.timeSignature().measureDuration().numerator(),
        measure.timeSignature().measureDuration().denominator());
  }

  synchronized boolean isOpen() {
    return document != null && player != null;
  }

  synchronized String exportXml() {
    if (document == null) throw new IllegalStateException("No score is open");
    return exporter.write(
        document.current(), document.state().keyId(), profiles(document.state().instruments()));
  }

  synchronized String exportPart(String partId) {
    var part = requirePart(partId);
    var score = document.current();
    return exporter.write(
        new Score(
            score.title(),
            List.of(part),
            score.repeatRegions(),
            score.endingRegions(),
            score.composer()),
        document.state().keyId(),
        profiles(document.state().instruments()));
  }

  private org.arpeon.domain.score.Part requirePart(String id) {
    return document.current().parts().stream()
        .filter(p -> p.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown part"));
  }

  synchronized PartImportView stageParts(byte[] bytes, String filename, boolean compressed)
      throws IOException {
    if (document == null)
      throw new IllegalStateException("Abra a composição antes de adicionar partes.");
    var source = compressed ? importer.readMxl(bytes, filename) : importer.read(bytes, filename);
    new ScoreTimeline(source.score());
    var detected = new ArrayList<ImportPartView>();
    for (var part : source.score().parts()) {
      String problem = "";
      try {
        org.arpeon.transport.PartAlignment.align(
            document.current(),
            source.score(),
            part.id(),
            "candidate",
            List.of(new org.arpeon.transport.PartAlignment.Anchor(0, 0)),
            false);
      } catch (IllegalArgumentException failure) {
        problem = failure.getMessage();
      }
      detected.add(
          new ImportPartView(
              part.id(),
              part.name(),
              source.instruments().getOrDefault(part.id(), "concert"),
              part.measures().size(),
              problem.isEmpty(),
              problem));
    }
    partImport = new PartImport(UUID.randomUUID().toString(), document.revision(), source);
    return new PartImportView(partImport.token(), filename, List.copyOf(detected));
  }

  private View commitImport(JsonObject request) {
    keys(request, "token", "partIds", "replaceId", "anchors", "manual");
    if (partImport == null
        || partImport.revision() != document.revision()
        || !partImport.token().equals(string(request, "token")))
      throw new IllegalArgumentException("A composição mudou. Importe o arquivo novamente.");
    var selected =
        com.google.gson.JsonParser.parseString(string(request, "partIds")).getAsJsonArray();
    if (selected.isEmpty() || selected.size() > MusicXmlImporter.MAX_PARTS)
      throw new IllegalArgumentException("Selecione uma parte.");
    var anchors = new ArrayList<org.arpeon.transport.PartAlignment.Anchor>();
    for (var element :
        com.google.gson.JsonParser.parseString(string(request, "anchors")).getAsJsonArray()) {
      var anchor = element.getAsJsonObject();
      keys(anchor, "sourceMeasure", "compositionMeasure");
      anchors.add(
          new org.arpeon.transport.PartAlignment.Anchor(
              integer(anchor, "sourceMeasure"), integer(anchor, "compositionMeasure")));
    }
    String replacement = string(request, "replaceId");
    if (!replacement.isEmpty()) {
      requirePart(replacement);
      if (selected.size() != 1)
        throw new IllegalArgumentException("Selecione uma parte para substituir.");
    }
    if (!request.get("manual").isJsonPrimitive()
        || !request.get("manual").getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException("Expected manual alignment boolean");
    boolean manual = request.get("manual").getAsBoolean();
    var score = document.current();
    var sourceScore = partImport.source().score();
    if (!java.util.Objects.equals(document.state().keyId(), partImport.source().keyId()))
      throw new IllegalArgumentException(
          "Tonalidades diferentes. Adapte a tonalidade antes de integrar a parte.");
    var parts = new ArrayList<>(score.parts());
    var instruments = new LinkedHashMap<>(document.state().instruments());
    var origins = new LinkedHashMap<>(partOrigins);
    var seen = new java.util.HashSet<String>();
    for (var element : selected) {
      String sourceId = element.getAsString();
      if (!seen.add(sourceId))
        throw new IllegalArgumentException("Parte selecionada mais de uma vez.");
      String id =
          replacement.isEmpty() ? "P" + UUID.randomUUID().toString().replace("-", "") : replacement;
      var aligned =
          org.arpeon.transport.PartAlignment.align(
              score, sourceScore, sourceId, id, anchors, manual);
      if (replacement.isEmpty()) parts.add(aligned);
      else parts.set(parts.indexOf(requirePart(replacement)), aligned);
      instruments.put(id, partImport.source().instruments().getOrDefault(sourceId, "concert"));
      origins.put(
          id,
          new RehearsalState.PartOrigin(
              partImport.source().sourceFilename(),
              partImport.source().sha256(),
              sourceId,
              anchors,
              manual));
    }
    if (parts.size() > 15)
      throw new IllegalArgumentException("O sintetizador suporta até 15 partes melódicas.");
    commit(
        new ScoreDocument.State(
            new Score(
                score.title(),
                parts,
                score.repeatRegions(),
                score.endingRegions(),
                score.composer()),
            instruments,
            document.state().keyId()));
    partOrigins.clear();
    partOrigins.putAll(origins);
    partImport = null;
    return snapshot();
  }

  private View managePart(String action, JsonObject request) {
    String id = part(request);
    var selected = requirePart(id);
    var score = document.current();
    var parts = new ArrayList<>(score.parts());
    var assignments = new LinkedHashMap<>(document.state().instruments());
    if (action.equals("rename-part")) {
      keys(request, "partId", "name");
      String name = string(request, "name").strip();
      if (name.isBlank()
          || name.length() > 120
          || name.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Use um nome de 1 a 120 caracteres.");
      parts.set(
          parts.indexOf(selected),
          new org.arpeon.domain.score.Part(id, name, selected.measures(), selected.clefs()));
    } else if (action.equals("move-part")) {
      keys(request, "partId", "index");
      int index = integer(request, "index");
      if (index < 0 || index >= parts.size())
        throw new IllegalArgumentException("Invalid part position");
      parts.remove(selected);
      parts.add(index, selected);
    } else {
      keys(request, "partId");
      if (parts.size() == 1)
        throw new IllegalArgumentException("A composição precisa de uma parte.");
      parts.remove(selected);
      assignments.remove(id);
    }
    commit(
        new ScoreDocument.State(
            new Score(
                score.title(),
                parts,
                score.repeatRegions(),
                score.endingRegions(),
                score.composer()),
            assignments,
            document.state().keyId()));
    hiddenParts.removeIf(partId -> parts.stream().noneMatch(p -> p.id().equals(partId)));
    if (parts.stream().allMatch(p -> hiddenParts.contains(p.id())))
      hiddenParts.remove(parts.getFirst().id());
    return snapshot();
  }

  synchronized ProjectStore.Data projectData() {
    if (document == null) throw new IllegalStateException("No score is open");
    return new ProjectStore.Data(
        document.current().title(),
        imported.sourceFilename(),
        imported.sha256(),
        imported.parserVersion(),
        sourceKind,
        importWarnings,
        source,
        exportXml(),
        rehearsal());
  }

  synchronized int revision() {
    if (document == null) throw new IllegalStateException("No score is open");
    return document.revision() + preferenceRevision;
  }

  synchronized View openProject(ProjectStore.Data data) throws IOException {
    if (data == null) throw new IllegalArgumentException("Invalid project");
    var parsed =
        importer.read(
            data.xml().getBytes(java.nio.charset.StandardCharsets.UTF_8), "saved-project.musicxml");
    var restored =
        new MusicXmlImporter.ImportedScore(
            parsed.score(),
            data.filename(),
            data.sha256(),
            data.parserVersion(),
            parsed.keyId(),
            parsed.instruments());
    if (data.rehearsal() != null) {
      try (var candidate =
          new MidiScorePlayer(new ScoreTimeline(parsed.score()), profiles(parsed.instruments()))) {
        candidate.restore(data.rehearsal().playback());
        if (parsed.score().parts().stream()
            .allMatch(p -> data.rehearsal().hiddenParts().contains(p.id())))
          throw new IllegalArgumentException("O projeto precisa de uma parte visível.");
      } catch (IllegalArgumentException failure) {
        throw new IOException(
            "Configurações de ensaio inválidas: " + failure.getMessage(), failure);
      }
    }
    openImported(restored, data.sourceKind(), data.importWarnings(), data.source());
    if (data.rehearsal() != null) {
      player.restore(data.rehearsal().playback());
      viewMode = data.rehearsal().viewMode();
      hiddenParts.addAll(data.rehearsal().hiddenParts());
      partOrigins.putAll(data.rehearsal().origins());
    }
    return snapshot();
  }

  synchronized MidiScorePlayer.State transport() {
    if (player == null) throw new IllegalStateException("No score is open");
    return player.snapshot();
  }

  synchronized AnalysisSource analysisSource(String partId) {
    if (document == null) throw new IllegalStateException("No score is open");
    int partIndex = -1;
    for (int i = 0; i < document.current().parts().size(); i++)
      if (document.current().parts().get(i).id().equals(partId)) partIndex = i;
    if (partIndex < 0) throw new IllegalArgumentException("Unknown score part");
    final int selectedPartIndex = partIndex;
    var part = document.current().parts().get(partIndex);
    int transposition =
        InstrumentCatalog.get(document.state().instruments().get(partId))
            .soundingTranspositionSemitones();
    var selectedNotes =
        timeline.notes().stream()
            .filter(note -> note.partIndex() == selectedPartIndex)
            .sorted(java.util.Comparator.comparingLong(ScoreTimeline.NotePosition::startTick))
            .toList();
    long previousEnd = -1;
    for (var note : selectedNotes) {
      if (note.startTick() < previousEnd)
        throw new IllegalArgumentException(
            "Live analysis supports one note at a time; choose a monophonic part");
      previousEnd = Math.addExact(note.startTick(), note.durationTicks());
    }
    var expected = new ArrayList<ExpectedEvent>();
    var expectedTicks = new ArrayList<Long>();
    var locations = new ArrayList<AnalysisStudy.Location>();
    var execution = org.arpeon.transport.ExecutionMap.expand(document.current());
    var eventPages = new java.util.HashMap<Integer, MeasureEventsPage>();
    for (var note : selectedNotes) {
      if (expected.size() == PerformanceAnalyzer.MAX_EXPECTED_EVENTS)
        throw new IllegalArgumentException("Analysis supports up to 2048 notes");
      double onset = 1000 * timeline.secondsAt(note.startTick());
      double end = 1000 * timeline.secondsAt(note.startTick() + note.durationTicks());
      expected.add(
          new ExpectedEvent(
              expected.size(), note.midiNumber() + transposition, onset, end - onset));
      expectedTicks.add(note.startTick());
      int occurrence = timeline.measureIndexAt(note.startTick());
      int written = execution.get(occurrence).measureIndex();
      var measure = timeline.measures().get(occurrence);
      long localTick = note.startTick() - measure.startTick();
      var page = eventPages.computeIfAbsent(written, index -> measureEvents(partId, index));
      var event =
          page.events().stream()
              .filter(
                  candidate ->
                      !candidate.kind().equals("REST")
                          && candidate.onsetNumerator() * 1920 / candidate.onsetDenominator()
                              == localTick)
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("Analysis note location missing"));
      locations.add(
          new AnalysisStudy.Location(
              expected.size() - 1,
              written,
              page.number(),
              event.index(),
              occurrence,
              timeline.measureIndexAt(note.startTick() + note.durationTicks() - 1)));
    }
    if (expected.isEmpty()) throw new IllegalArgumentException("Selected part has no notes");
    double duration = 1000 * timeline.secondsAt(timeline.totalTicks());
    if (duration > 600_000)
      throw new IllegalArgumentException("Analysis supports up to ten minutes");
    return new AnalysisSource(
        document.current().title(),
        imported.sourceFilename(),
        imported.sha256(),
        document.revision(),
        partId,
        part.name(),
        transposition,
        duration,
        timeline.firstBpm(),
        List.copyOf(expected),
        List.copyOf(expectedTicks),
        List.copyOf(locations),
        scoreXmlSha256());
  }

  private String scoreXmlSha256() {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(exportXml().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable", unavailable);
    }
  }

  record StudySelection(MidiScorePlayer.State transport, AnalysisStudy.Location location) {}

  synchronized StudySelection prepareStudy(JsonObject request) {
    keys(
        request,
        "sha256",
        "scoreXmlSha256",
        "revision",
        "partId",
        "soundingSemitones",
        "expectedIndex",
        "bpm",
        "repeat");
    String sha256 = string(request, "sha256");
    String xmlHash = string(request, "scoreXmlSha256");
    int revision = integer(request, "revision");
    String partId = string(request, "partId");
    int transposition = integer(request, "soundingSemitones");
    int index = integer(request, "expectedIndex");
    var tempo = request.get("bpm");
    var repeat = request.get("repeat");
    if (tempo == null
        || !tempo.isJsonPrimitive()
        || !tempo.getAsJsonPrimitive().isNumber()
        || repeat == null
        || !repeat.isJsonPrimitive()
        || !repeat.getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException("Informe o andamento e a repetição do trecho.");
    double bpm = tempo.getAsDouble();
    if (!Double.isFinite(bpm) || bpm < 30 || bpm > 300)
      throw new IllegalArgumentException("Escolha um andamento entre 30 e 300 BPM.");
    if (document == null || !imported.sha256().equals(sha256) || document.revision() != revision)
      throw new IllegalStateException(
          "A partitura mudou. Analise esta revisão antes de preparar o trecho.");
    var source = analysisSource(partId);
    if (source.soundingSemitones() != transposition)
      throw new IllegalStateException(
          "O instrumento mudou. Analise novamente para usar este trecho.");
    if (index < 0 || index >= source.locations().size())
      throw new IllegalArgumentException("Nota do relatório fora da partitura.");
    if (!source.scoreXmlSha256().equals(xmlHash))
      throw new IllegalStateException(
          "O conteúdo da partitura mudou. Analise novamente antes de preparar este trecho.");
    var location = source.locations().get(index);
    int end = location.endMeasureIndex();
    // Include continuations of every note that starts in this performed measure.
    if (repeat.getAsBoolean())
      for (var candidate : source.locations())
        if (candidate.executionMeasureIndex() == location.executionMeasureIndex())
          end = Math.max(end, candidate.endMeasureIndex());
    player.pause();
    player.selectPart(partId);
    player.setLoop(location.executionMeasureIndex(), end, repeat.getAsBoolean());
    if (repeat.getAsBoolean()) player.setBpm(bpm);
    player.seek(
        repeat.getAsBoolean()
            ? timeline.measures().get(location.executionMeasureIndex()).startTick()
            : source.expectedTicks().get(index));
    return new StudySelection(player.snapshot(), location);
  }

  synchronized View edit(String action, JsonObject request) {
    if (document == null) throw new IllegalStateException("No score is open");
    if (action.equals("cancel-import")) {
      keys(request, "token");
      if (partImport != null && partImport.token().equals(string(request, "token")))
        partImport = null;
      return snapshot();
    }
    if (action.equals("commit-import")) return commitImport(request);
    if (action.equals("view-mode")) {
      keys(request, "mode");
      String mode = string(request, "mode");
      if (!Set.of("full", "individual", "focus").contains(mode))
        throw new IllegalArgumentException("Invalid view mode");
      viewMode = mode;
      preferenceRevision++;
      return snapshot();
    }
    if (action.equals("part-visibility")) {
      keys(request, "partId", "visible");
      String id = part(request);
      requirePart(id);
      if (!request.get("visible").isJsonPrimitive()
          || !request.get("visible").getAsJsonPrimitive().isBoolean())
        throw new IllegalArgumentException("Expected visibility boolean");
      if (request.get("visible").getAsBoolean()) hiddenParts.remove(id);
      else hiddenParts.add(id);
      if (hiddenParts.size() >= document.current().parts().size()) {
        hiddenParts.remove(id);
        throw new IllegalArgumentException("Mantenha pelo menos uma parte visível.");
      }
      preferenceRevision++;
      return snapshot();
    }
    if (Set.of("rename-part", "remove-part", "move-part").contains(action))
      return managePart(action, request);
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
      keys(
          request,
          withVoice(
              request,
              request != null && request.has("headIndex")
                  ? new String[] {
                    "partId", "measureIndex", "eventIndex", "headIndex", "step", "alter", "octave"
                  }
                  : new String[] {
                    "partId", "measureIndex", "eventIndex", "step", "alter", "octave"
                  }));
      candidate =
          scoreState(
              ScoreDocument.setPitch(
                  score,
                  part(request),
                  index(request, "measureIndex"),
                  index(request, "eventIndex"),
                  request.has("voiceId") ? string(request, "voiceId") : "1",
                  selectedHeadIndex(request, score),
                  pitch(request)));
    } else if (action.equals("set-duration")) {
      keys(
          request,
          withVoice(request, "partId", "measureIndex", "eventIndex", "numerator", "denominator"));
      candidate =
          scoreState(
              ScoreDocument.changeDuration(
                  score,
                  part(request),
                  index(request, "measureIndex"),
                  index(request, "eventIndex"),
                  request.has("voiceId") ? string(request, "voiceId") : "1",
                  duration(request)));
    } else if (action.equals("insert-note") || action.equals("insert-rest")) {
      if (action.equals("insert-note"))
        keys(
            request,
            withVoice(
                request,
                "partId",
                "measureIndex",
                "eventIndex",
                "numerator",
                "denominator",
                "step",
                "alter",
                "octave"));
      else
        keys(
            request,
            withVoice(request, "partId", "measureIndex", "eventIndex", "numerator", "denominator"));
      candidate =
          scoreState(
              ScoreDocument.insertIntoRest(
                  score,
                  part(request),
                  index(request, "measureIndex"),
                  index(request, "eventIndex"),
                  request.has("voiceId") ? string(request, "voiceId") : "1",
                  duration(request),
                  action.equals("insert-note") ? pitch(request) : null));
    } else if (action.equals("delete")) {
      keys(
          request,
          withVoice(
              request,
              request != null && request.has("headIndex")
                  ? new String[] {"partId", "measureIndex", "eventIndex", "headIndex"}
                  : new String[] {"partId", "measureIndex", "eventIndex"}));
      candidate =
          scoreState(
              ScoreDocument.deleteNote(
                  score,
                  part(request),
                  index(request, "measureIndex"),
                  index(request, "eventIndex"),
                  request.has("voiceId") ? string(request, "voiceId") : "1",
                  selectedHeadIndex(request, score)));
    } else if (action.equals("remove-tie")) {
      keys(
          request,
          withVoice(
              request,
              request != null && request.has("headIndex")
                  ? new String[] {"partId", "measureIndex", "eventIndex", "headIndex"}
                  : new String[] {"partId", "measureIndex", "eventIndex"}));
      candidate =
          scoreState(
              ScoreDocument.removeTie(
                  score,
                  part(request),
                  index(request, "measureIndex"),
                  index(request, "eventIndex"),
                  request.has("voiceId") ? string(request, "voiceId") : "1",
                  selectedHeadIndex(request, score)));
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
            changes.changedHeads(),
            changes.events().stream().limit(24).toList(),
            warnings(score, state.instruments()),
            warnings(candidate.score(), candidate.instruments()),
            exporter.write(
                candidate.score(), candidate.keyId(), profiles(candidate.instruments())));
    pending = new Pending(document.revision(), candidate, view);
    return snapshot();
  }

  private static ChangeSet changes(Score before, Score after, String partId) {
    var result = new ArrayList<PitchChange>();
    int changedHeads = 0;
    for (int p = 0; p < before.parts().size(); p++) {
      var original = before.parts().get(p);
      if (partId != null && !original.id().equals(partId)) continue;
      var changed = after.parts().get(p);
      for (int m = 0; m < original.measures().size(); m++) {
        for (var oldVoice : original.measures().get(m).voices()) {
          var newVoice =
              changed.measures().get(m).voices().stream()
                  .filter(voice -> voice.id().equals(oldVoice.id()))
                  .findFirst()
                  .orElseThrow(
                      () -> new IllegalArgumentException("Pitch transform changed voices"));
          if (oldVoice.events().size() != newVoice.events().size())
            throw new IllegalArgumentException("Pitch transform changed voice event structure");
          for (int e = 0; e < oldVoice.events().size(); e++) {
            var oldHeads = oldVoice.events().get(e).event().noteHeads();
            var newHeads = newVoice.events().get(e).event().noteHeads();
            if (oldHeads.size() != newHeads.size())
              throw new IllegalArgumentException("Pitch transform changed chord structure");
            int changedInEvent = 0;
            for (int h = 0; h < oldHeads.size(); h++)
              if (!oldHeads.get(h).writtenPitch().equals(newHeads.get(h).writtenPitch()))
                changedInEvent++;
            if (changedInEvent > 0) {
              changedHeads = Math.addExact(changedHeads, changedInEvent);
              result.add(
                  new PitchChange(
                      original.id(),
                      m,
                      oldVoice.id(),
                      e,
                      oldHeads.stream()
                          .map(head -> head.writtenPitch().label())
                          .collect(java.util.stream.Collectors.joining(" + ")),
                      newHeads.stream()
                          .map(head -> head.writtenPitch().label())
                          .collect(java.util.stream.Collectors.joining(" + "))));
            }
          }
        }
      }
    }
    return new ChangeSet(List.copyOf(result), changedHeads);
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
    long events =
        candidate.score().parts().stream()
            .flatMap(p -> p.measures().stream())
            .flatMap(m -> m.voices().stream())
            .flatMap(v -> v.events().stream())
            .mapToLong(e -> Math.max(1, e.event().noteHeads().size()))
            .sum();
    if (events > MusicXmlImporter.MAX_SCORE_EVENTS)
      throw new IllegalArgumentException(
          "A composição excede o limite de "
              + MusicXmlImporter.MAX_SCORE_EVENTS
              + " eventos musicais.");
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
    next.restore(player.settings());
    if (previous.loop() != null
        && previous.loop().endMeasureIndex() < nextTimeline.measures().size()) {
      next.setLoop(previous.loop().startMeasureIndex(), previous.loop().endMeasureIndex(), true);
      var range = next.snapshot().loop();
      next.seek(Math.max(range.startTick(), Math.min(previous.tick(), range.endTick() - 1)));
    } else next.seek(Math.min(previous.tick(), nextTimeline.totalTicks()));
    player.close();
    player = next;
    timeline = nextTimeline;
  }

  private List<InstrumentProfile> instrumentChoices() {
    var choices = new LinkedHashMap<String, InstrumentProfile>();
    InstrumentCatalog.profiles().forEach(profile -> choices.put(profile.id(), profile));
    profiles(document.state().instruments())
        .values()
        .forEach(profile -> choices.put(profile.id(), profile));
    return List.copyOf(choices.values());
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

  private static int selectedHeadIndex(JsonObject request, Score score) {
    if (request.has("headIndex")) return index(request, "headIndex");
    String partId = part(request);
    int measureIndex = index(request, "measureIndex");
    int eventIndex = index(request, "eventIndex");
    var selectedPart =
        score.parts().stream()
            .filter(part -> part.id().equals(partId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown part"));
    if (measureIndex < 0 || measureIndex >= selectedPart.measures().size())
      throw new IllegalArgumentException("Measure outside score");
    String voiceId = request.has("voiceId") ? string(request, "voiceId") : "1";
    var voice =
        selectedPart.measures().get(measureIndex).voices().stream()
            .filter(candidate -> candidate.id().equals(voiceId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown voice"));
    if (eventIndex < 0 || eventIndex >= voice.events().size())
      throw new IllegalArgumentException("Event outside measure");
    if (voice.events().get(eventIndex).event() instanceof Chord)
      throw new IllegalArgumentException("Select a chord head explicitly");
    return 0;
  }

  private static String[] withVoice(JsonObject request, String... keys) {
    if (request == null || !request.has("voiceId")) return keys;
    var result = new ArrayList<String>(List.of(keys));
    int partIndex = result.indexOf("partId");
    result.add(partIndex < 0 ? 0 : partIndex + 1, "voiceId");
    return result.toArray(String[]::new);
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
      case "loop" -> {
        exactTransportKeys(request, "startMeasureIndex", "endMeasureIndex", "enabled");
        player.setLoop(
            request.get("startMeasureIndex").getAsBigDecimal().intValueExact(),
            request.get("endMeasureIndex").getAsBigDecimal().intValueExact(),
            request.get("enabled").getAsBoolean());
      }
      case "select-part" -> {
        exactTransportKeys(request, "id");
        player.selectPart(request.get("id").getAsString());
      }
      case "mute" -> {
        exactTransportKeys(request, "id", "enabled");
        player.setMute(request.get("id").getAsString(), request.get("enabled").getAsBoolean());
      }
      case "volume" -> {
        exactTransportKeys(request, "id", "value");
        player.setVolume(string(request, "id"), request.get("value").getAsDouble());
      }
      case "pan" -> {
        exactTransportKeys(request, "id", "value");
        player.setPan(string(request, "id"), request.get("value").getAsDouble());
      }
      case "master" -> {
        exactTransportKeys(request, "value");
        player.setMaster(request.get("value").getAsDouble());
      }
      case "preset" -> {
        keys(request, "preset");
        player.preset(string(request, "preset"));
      }
      case "retry-sounds" -> {
        keys(request);
        player.retrySounds();
      }
      case "solo" -> {
        exactTransportKeys(request, "id", "enabled");
        player.setSolo(request.get("id").getAsString(), request.get("enabled").getAsBoolean());
      }
      default -> throw new IllegalArgumentException("Unknown transport action");
    }
    if (!Set.of("play", "pause", "stop", "seek", "retry-sounds").contains(action))
      preferenceRevision++;
    return snapshot();
  }

  synchronized void stopIfPlaying() {
    if (player != null && player.snapshot().status().equals("PLAYING")) player.stop();
  }

  synchronized void pauseIfPlaying() {
    if (player != null && player.snapshot().status().equals("PLAYING")) player.pause();
  }

  private static void exactTransportKeys(JsonObject object, String... names) {
    keys(object, names);
    for (String name : names) {
      var value = object.get(name);
      if (value == null
          || !value.isJsonPrimitive()
          || name.equals("enabled") && !value.getAsJsonPrimitive().isBoolean()
          || name.equals("id") && !value.getAsJsonPrimitive().isString()
          || (name.equals("value")
                  || name.equals("tick")
                  || name.equals("bpm")
                  || name.endsWith("MeasureIndex"))
              && !value.getAsJsonPrimitive().isNumber())
        throw new IllegalArgumentException("Invalid transport parameter");
    }
  }

  @Override
  public synchronized void close() {
    if (player != null) player.close();
  }
}
