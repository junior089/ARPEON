package org.arpeon.desktop;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.arpeon.omr.OmrEngine;
import org.arpeon.omr.OmrInput;
import org.arpeon.omr.OmrInputValidator;
import org.arpeon.omr.OmrResult;

/** Holds one recognition result for explicit human review before opening a score. */
final class OmrWorkspace {
  record MeasureReview(String part, String number, List<String> events) {}

  record Preview(
      String token,
      String title,
      String filename,
      String sha256,
      int pages,
      int parts,
      int measures,
      List<MeasureReview> recognizedMeasures,
      List<Integer> pagesRequiringReview,
      List<String> measuresRequiringReview,
      List<String> warnings,
      List<String> unsupportedSymbols,
      String xml) {}

  private record Pending(Preview preview, OmrResult result, String jobId) {}

  private static final class Recognition {
    final String id;
    final Thread thread;
    volatile boolean cancelled;
    volatile String phase = "reading";

    Recognition(String id) {
      this.id = id;
      this.thread = Thread.currentThread();
    }
  }

  private final OmrEngine engine;
  private Pending pending;
  private Recognition recognition;
  private String lastPhase = "idle";
  private final java.util.LinkedHashSet<String> cancelledBeforeStart =
      new java.util.LinkedHashSet<>();

  OmrWorkspace(OmrEngine engine) {
    this.engine = engine;
  }

  boolean available() {
    return engine.available();
  }

  synchronized Status status() {
    return new Status(
        engine.available(),
        recognition != null,
        recognition == null ? lastPhase : recognition.phase,
        recognition == null ? null : recognition.id);
  }

  record Status(boolean available, boolean recognizing, String recognitionPhase, String jobId) {}

  Preview preview(byte[] bytes, String filename) throws IOException {
    return preview(bytes, filename, UUID.randomUUID().toString());
  }

  Preview preview(byte[] bytes, String filename, String jobId) throws IOException {
    Recognition job;
    synchronized (this) {
      if (cancelledBeforeStart.remove(jobId)) throw new IOException("Reconhecimento cancelado.");
      if (recognition != null)
        throw new IllegalStateException("Já existe um reconhecimento em andamento.");
      job = new Recognition(jobId);
      recognition = job;
      pending = null;
    }
    try {
      return recognize(bytes, filename, job);
    } finally {
      synchronized (this) {
        lastPhase = job.cancelled ? "cancelled" : pending == null ? "failed" : "ready";
        recognition = null;
      }
    }
  }

  private Preview recognize(byte[] bytes, String filename, Recognition job) throws IOException {
    if (!engine.available())
      throw new IllegalStateException(
          "O reconhecimento óptico não está disponível. Reinstale o pacote completo do ARPEON.");
    var input = new OmrInput(bytes, filename);
    OmrInputValidator.validate(input);
    var result =
        engine.recognize(
            input,
            phase -> {
              if (!job.cancelled) job.phase = phase;
            });
    var score = result.imported().score();
    int measures = score.parts().stream().mapToInt(part -> part.measures().size()).max().orElse(0);
    int eventCount =
        score.parts().stream()
            .flatMap(part -> part.measures().stream())
            .mapToInt(
                measure ->
                    measure.voices().stream()
                        .flatMap(voice -> voice.events().stream())
                        .mapToInt(event -> Math.max(1, event.event().noteHeads().size()))
                        .sum())
            .sum();
    int measureCount = score.parts().stream().mapToInt(part -> part.measures().size()).sum();
    if (score.parts().size() > 16 || measureCount > 2048 || eventCount > 16_384)
      throw new IOException("OMR preview exceeds 16 parts, 2048 measures or 16384 events");
    var recognized = new java.util.ArrayList<MeasureReview>();
    for (var part : score.parts())
      for (var measure : part.measures()) {
        var events =
            measure.voices().stream()
                .flatMap(
                    voice ->
                        voice.events().stream()
                            .map(
                                timed -> {
                                  var event = timed.event();
                                  String label =
                                      event.noteHeads().isEmpty()
                                          ? "Pausa"
                                          : event.noteHeads().stream()
                                              .map(note -> note.writtenPitch().label())
                                              .collect(java.util.stream.Collectors.joining(" + "));
                                  return "Voz "
                                      + voice.id()
                                      + " · "
                                      + timed.onset().numerator()
                                      + "/"
                                      + timed.onset().denominator()
                                      + " · "
                                      + label
                                      + " · "
                                      + event.duration().numerator()
                                      + "/"
                                      + event.duration().denominator();
                                }))
                .toList();
        recognized.add(new MeasureReview(part.name(), measure.number(), events));
      }
    var view =
        new Preview(
            UUID.randomUUID().toString(),
            score.title(),
            result.sourceFilename(),
            result.sourceSha256(),
            result.pages(),
            score.parts().size(),
            measures,
            List.copyOf(recognized),
            result.pagesRequiringReview(),
            recognized.stream().map(measure -> measure.part() + " · " + measure.number()).toList(),
            result.warnings(),
            result.unsupportedSymbols(),
            new org.arpeon.musicxml.MusicXmlExporter()
                .write(
                    score,
                    result.imported().keyId(),
                    result.imported().instruments().entrySet().stream()
                        .collect(
                            java.util.stream.Collectors.toMap(
                                java.util.Map.Entry::getKey,
                                entry ->
                                    org.arpeon.domain.instrument.InstrumentCatalog.get(
                                        entry.getValue())))));
    synchronized (this) {
      if (job.cancelled) throw new IOException("Reconhecimento cancelado.");
      pending = new Pending(view, result, job.id);
    }
    return view;
  }

  synchronized boolean cancel(String jobId) {
    if (recognition != null && recognition.id.equals(jobId)) {
      recognition.cancelled = true;
      recognition.phase = "cancelling";
      if (pending != null && pending.jobId().equals(jobId)) pending = null;
      recognition.thread.interrupt();
      return true;
    }
    if (pending != null && pending.jobId().equals(jobId)) {
      pending = null;
      lastPhase = "cancelled";
      return true;
    }
    if (recognition != null) return false;
    cancelledBeforeStart.add(jobId);
    if (cancelledBeforeStart.size() > 64) cancelledBeforeStart.removeFirst();
    return true;
  }

  synchronized OmrResult accept(String token) {
    if (pending == null || !pending.preview().token().equals(token))
      throw new IllegalStateException("OMR preview expired; recognize the score again");
    return pending.result();
  }

  synchronized void clear() {
    pending = null;
  }

  synchronized void close() {
    if (recognition != null) cancel(recognition.id);
    pending = null;
  }
}
