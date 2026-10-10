package org.arpeon.analysis;

import java.util.ArrayList;
import java.util.List;

/** Segments successive stable pitch windows; a repeated pitch needs an observed gap. */
public final class OnsetSegmenter {
  private final long plannedStartEpochMs;
  private final double referenceHz;
  private final int latencyCompensationMs;
  private final List<ObservedEvent> events = new ArrayList<>();
  private long lastCaptureEpochMs = Long.MIN_VALUE;
  private int samples;
  private int midi;
  private double onsetMs;
  private double endMs;
  private double centsSum;
  private double confidenceSum;
  private boolean finished;

  public OnsetSegmenter(long plannedStartEpochMs, double referenceHz, int latencyCompensationMs) {
    if (plannedStartEpochMs <= 0
        || !Double.isFinite(referenceHz)
        || referenceHz < 400
        || referenceHz > 480
        || latencyCompensationMs < 0
        || latencyCompensationMs > 500)
      throw new IllegalArgumentException("Invalid capture clock or tuning reference");
    this.plannedStartEpochMs = plannedStartEpochMs;
    this.referenceHz = referenceHz;
    this.latencyCompensationMs = latencyCompensationMs;
  }

  public void accept(long capturedEpochMs, Double frequencyHz, double confidence, double frameMs) {
    accept(capturedEpochMs, frequencyHz, confidence, frameMs, false);
  }

  public void accept(
      long capturedEpochMs,
      Double frequencyHz,
      double confidence,
      double frameMs,
      boolean attackCandidate) {
    if (finished) throw new IllegalStateException("Analysis capture is closed");
    if (!Double.isFinite(frameMs)
        || frameMs <= 0
        || !Double.isFinite(confidence)
        || confidence < 0
        || confidence > 1) throw new IllegalArgumentException("Invalid analysis window");
    if (capturedEpochMs <= lastCaptureEpochMs || capturedEpochMs < plannedStartEpochMs) return;
    long gapMs = lastCaptureEpochMs == Long.MIN_VALUE ? 0 : capturedEpochMs - lastCaptureEpochMs;
    lastCaptureEpochMs = capturedEpochMs;
    double positionMs = Math.max(0, capturedEpochMs - plannedStartEpochMs - latencyCompensationMs);
    if (frequencyHz == null
        || !Double.isFinite(frequencyHz)
        || frequencyHz <= 0
        || !Double.isFinite(confidence)
        || confidence < 0.65) {
      close();
      return;
    }
    int nearest = (int) Math.round(69 + 12 * Math.log(frequencyHz / referenceHz) / Math.log(2));
    if (nearest < 0 || nearest > 127) {
      close();
      return;
    }
    if (samples > 0
        && (nearest != midi || gapMs > 350 || attackCandidate && positionMs - onsetMs >= 80))
      close();
    if (samples == 0) {
      if (events.size() >= PerformanceAnalyzer.MAX_OBSERVED_EVENTS)
        throw new IllegalStateException("Analysis reached 4096 observed events");
      midi = nearest;
      onsetMs = positionMs;
    }
    samples++;
    centsSum +=
        1200
            * Math.log(frequencyHz / (referenceHz * Math.pow(2, (midi - 69) / 12.0)))
            / Math.log(2);
    confidenceSum += confidence;
    endMs = Math.max(onsetMs + 1, positionMs + Math.clamp(frameMs, 1, 200));
  }

  public List<ObservedEvent> finish() {
    if (!finished) {
      close();
      finished = true;
    }
    return List.copyOf(events);
  }

  public int eventCount() {
    return events.size() + (samples > 0 ? 1 : 0);
  }

  private void close() {
    if (samples == 0) return;
    events.add(
        new ObservedEvent(
            events.size(),
            midi,
            onsetMs,
            endMs - onsetMs,
            centsSum / samples,
            confidenceSum / samples,
            samples));
    samples = 0;
    centsSum = 0;
    confidenceSum = 0;
  }
}
