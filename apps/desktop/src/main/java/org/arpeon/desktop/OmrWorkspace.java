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
      List<String> unsupportedSymbols) {}

  private record Pending(Preview preview, OmrResult result) {}

  private final OmrEngine engine;
  private Pending pending;

  OmrWorkspace(OmrEngine engine) {
    this.engine = engine;
  }

  boolean available() {
    return engine.available();
  }

  boolean installable() {
    return engine.installable();
  }

  Status status() {
    var progress = engine.installProgress();
    boolean installing =
        List.of("downloading", "verifying", "extracting", "checking").contains(progress.phase());
    return new Status(
        engine.available(),
        engine.installable(),
        installing,
        progress.phase(),
        progress.downloadedBytes(),
        progress.totalBytes());
  }

  record Status(
      boolean available,
      boolean installable,
      boolean installing,
      String phase,
      long downloadedBytes,
      long totalBytes) {}

  boolean install() throws IOException {
    if (!engine.available()) engine.install();
    return engine.available();
  }

  synchronized Preview preview(byte[] bytes, String filename) throws IOException {
    pending = null;
    if (!engine.available()) throw new IllegalStateException("Audiveris is not configured");
    var input = new OmrInput(bytes, filename);
    OmrInputValidator.validate(input);
    var result = engine.recognize(input);
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
            result.unsupportedSymbols());
    pending = new Pending(view, result);
    return view;
  }

  synchronized OmrResult accept(String token) {
    if (pending == null || !pending.preview().token().equals(token))
      throw new IllegalStateException("OMR preview expired; recognize the score again");
    return pending.result();
  }

  synchronized void clear() {
    pending = null;
  }
}
