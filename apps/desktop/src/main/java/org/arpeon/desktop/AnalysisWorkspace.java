package org.arpeon.desktop;

import java.util.function.LongSupplier;
import org.arpeon.analysis.AnalysisConfig;
import org.arpeon.analysis.OnsetSegmenter;
import org.arpeon.analysis.PerformanceAnalyzer;
import org.arpeon.analysis.ScoreFollower;
import org.arpeon.tuner.TunerObservation;
import org.arpeon.tuner.TunerService;
import org.arpeon.tuner.TuningReference;

/** One bounded local microphone attempt; raw audio is never persisted. */
final class AnalysisWorkspace {
  record View(
      String status,
      String mode,
      String title,
      String filename,
      String sha256,
      Integer revision,
      String partId,
      String partName,
      Integer soundingSemitones,
      Integer expectedCount,
      Double durationMs,
      Double initialBpm,
      Long startsAtEpochMs,
      Long elapsedMs,
      int observedOnsets,
      java.util.List<org.arpeon.analysis.ExpectedEvent> expected,
      java.util.List<Long> expectedTicks,
      ScoreFollower.Position follow,
      AnalysisConfig config,
      PerformanceAnalyzer.Report report,
      String message,
      AnalysisStudy.Context study) {}

  private final ScoreWorkspace scores;
  private final TunerService tuner;
  private final LongSupplier clock;
  private ScoreWorkspace.AnalysisSource source;
  private AnalysisConfig config;
  private OnsetSegmenter segmenter;
  private ScoreFollower follower;
  private String mode = "ANALYSIS";
  private PerformanceAnalyzer.Report report;
  private long startsAt;
  private double referenceHz;
  private long lastObservedAt = Long.MIN_VALUE;
  private String status = "IDLE";
  private String message = "Abra uma partitura para analisar uma tentativa.";

  AnalysisWorkspace(ScoreWorkspace scores, TunerService tuner) {
    this(scores, tuner, System::currentTimeMillis);
  }

  AnalysisWorkspace(ScoreWorkspace scores, TunerService tuner, LongSupplier clock) {
    this.scores = scores;
    this.tuner = tuner;
    this.clock = clock;
  }

  synchronized View start(
      String partId,
      String deviceId,
      double referenceHz,
      boolean consent,
      AnalysisConfig selected) {
    return start(partId, deviceId, referenceHz, consent, selected, false);
  }

  synchronized View start(
      String partId,
      String deviceId,
      double referenceHz,
      boolean consent,
      AnalysisConfig selected,
      boolean follow) {
    if (!consent) throw new IllegalArgumentException("Explicit microphone consent is required");
    if (active()) throw new IllegalStateException("An analysis attempt is already active");
    var next = scores.analysisSource(partId);
    tuner.start(deviceId, new TuningReference(referenceHz));
    source = next;
    this.referenceHz = referenceHz;
    config = selected;
    mode = follow ? "FOLLOW" : "ANALYSIS";
    follower = follow ? new ScoreFollower(next.expected()) : null;
    report = null;
    startsAt = clock.getAsLong() + 2000;
    segmenter = new OnsetSegmenter(startsAt, referenceHz, selected.latencyCompensationMs());
    lastObservedAt = Long.MIN_VALUE;
    status = "COUNT_IN";
    message =
        follow
            ? "Comece após a preparação; a posição segue notas estáveis e espera durante pausas."
            : "A primeira batida começa em 2 segundos; toque no tempo da partitura.";
    return view();
  }

  synchronized View observe() {
    if (!active()) return view(false);
    TunerObservation observation = tuner.observation();
    if (observation.state() == TunerObservation.State.ERROR
        || observation.state() == TunerObservation.State.IDLE) {
      status = "ERROR";
      message = "A captura foi interrompida; esta tentativa não foi pontuada.";
      tuner.stop();
      return view(false);
    }
    long now = clock.getAsLong();
    if (now >= startsAt) status = "RECORDING";
    if (observation.capturedAt() != null) {
      long timestamp = observation.capturedAt().toEpochMilli();
      if (follower == null
          && timestamp > lastObservedAt
          && timestamp < startsAt + source.durationMs() + 1000) {
        boolean attackCandidate = tuner.hasAttackBetween(lastObservedAt, timestamp);
        lastObservedAt = timestamp;
        segmenter.accept(
            timestamp,
            observation.state() == TunerObservation.State.TRACKING
                ? observation.frequencyHz()
                : null,
            observation.confidence(),
            Math.max(1, observation.bufferLatencyMs()),
            attackCandidate);
      }
      if (follower != null && timestamp > lastObservedAt && timestamp >= startsAt) {
        // The fixed-tempo segmenter is unused in follow mode; advance only on new windows.
        boolean attackCandidate = tuner.hasAttackBetween(lastObservedAt, timestamp);
        lastObservedAt = timestamp;
        Integer midi = null;
        if (observation.state() == TunerObservation.State.TRACKING
            && observation.frequencyHz() != null
            && observation.frequencyHz() > 0) {
          midi =
              (int)
                  Math.round(
                      69 + 12 * Math.log(observation.frequencyHz() / referenceHz) / Math.log(2));
        }
        follower.accept(timestamp, midi, observation.confidence(), attackCandidate);
      }
    }
    if (follower != null) {
      if (now > startsAt + 1_200_000) {
        tuner.stop();
        status = "DONE";
        message = "Limite de vinte minutos atingido; acompanhamento encerrado.";
      }
      return view(false);
    }
    if (now > startsAt + source.durationMs() + 1000) return finish();
    return view(false);
  }

  synchronized View finish() {
    if (!active()) throw new IllegalStateException("No analysis attempt is active");
    if (follower != null) {
      tuner.stop();
      status = "DONE";
      message = "Acompanhamento encerrado.";
      return view();
    }
    if (clock.getAsLong() < startsAt + source.durationMs())
      throw new IllegalStateException("Wait until the score duration has elapsed");
    tuner.stop();
    report = PerformanceAnalyzer.analyze(source.expected(), segmenter.finish(), config);
    status = report.status();
    message =
        status.equals("INSUFFICIENT_DATA")
            ? "Nenhuma nota estável foi capturada; não há percentuais para esta tentativa."
            : "Tentativa analisada. Confira as métricas separadas e os desvios por nota.";
    return view();
  }

  synchronized View stop() {
    if (active()) tuner.stop();
    status = "STOPPED";
    report = null;
    message = "Tentativa cancelada; nenhuma pontuação foi gerada.";
    return view();
  }

  synchronized View view() {
    return view(true);
  }

  private View view(boolean includeScore) {
    if (source == null)
      return new View(
          status, mode, null, null, null, null, null, null, null, null, null, null, null, null, 0,
          null, null, null, null, null, message, null);
    return new View(
        status,
        mode,
        source.title(),
        source.filename(),
        source.sha256(),
        source.revision(),
        source.partId(),
        source.partName(),
        source.soundingSemitones(),
        source.expected().size(),
        source.durationMs(),
        source.initialBpm(),
        startsAt,
        Math.max(0, clock.getAsLong() - startsAt),
        follower == null
            ? (segmenter == null ? 0 : segmenter.eventCount())
            : follower.position().confirmedNotes(),
        includeScore ? source.expected() : null,
        includeScore ? source.expectedTicks() : null,
        follower == null ? null : follower.position(),
        config,
        report,
        message,
        includeScore
            ? new AnalysisStudy.Context(
                source.scoreXmlSha256(),
                source.initialBpm(),
                source.soundingSemitones(),
                config,
                source.locations())
            : null);
  }

  synchronized boolean active() {
    return status.equals("COUNT_IN") || status.equals("RECORDING");
  }
}
