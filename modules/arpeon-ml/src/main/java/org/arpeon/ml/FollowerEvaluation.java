package org.arpeon.ml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.arpeon.analysis.ExpectedEvent;
import org.arpeon.analysis.ScoreFollower;

/** Reproducible, synthetic-only evaluation of one optional score-following model. */
public final class FollowerEvaluation {
  public record Metrics(
      int observations,
      double accuracyPercent,
      int falseJumps,
      int remoteJumps,
      double meanRecoveryNotes,
      double p95LatencyMs) {}

  public record Result(String modelChecksum, Metrics baseline, Metrics candidate) {}

  private FollowerEvaluation() {}

  public static Result run() {
    var training = new ArrayList<List<LearnedFollower.Sample>>();
    for (int seed = 1; seed <= 400; seed++) training.add(trace(seed));
    LearnedFollower model = LearnedFollower.train(training);
    var baseline = new Collector();
    var candidate = new Collector();
    for (int seed = 10_001; seed <= 10_100; seed++) {
      var samples = trace(seed);
      int[] notes = samples.getFirst().score();
      var expected = new ArrayList<ExpectedEvent>();
      for (int i = 0; i < notes.length; i++)
        expected.add(new ExpectedEvent(i, notes[i], i * 500.0, 400));
      var follower = new ScoreFollower(expected);
      model.start(notes);
      int[] truth = new int[samples.size()];
      int[] baselinePositions = new int[samples.size()];
      int[] modelPositions = new int[samples.size()];
      for (int i = 0; i < samples.size(); i++) {
        var sample = samples.get(i);
        truth[i] = sample.position();
        long timestamp = 1_000L + i * 600L;
        long began = System.nanoTime();
        follower.accept(timestamp, sample.midi(), .9);
        baselinePositions[i] = follower.accept(timestamp + 100, sample.midi(), .9).noteIndex();
        baseline.latencies.add(System.nanoTime() - began);
        began = System.nanoTime();
        modelPositions[i] = model.observe(sample.midi());
        candidate.latencies.add(System.nanoTime() - began);
      }
      baseline.add(truth, baselinePositions);
      candidate.add(truth, modelPositions);
    }
    return new Result(model.checksum(), baseline.metrics(), candidate.metrics());
  }

  public static void main(String[] args) {
    Result result = run();
    System.out.printf(
        Locale.ROOT,
        "{\"trainingTraces\":400,\"heldOutTraces\":100,\"modelChecksum\":\"%s\","
            + "\"baseline\":%s,\"candidate\":%s}%n",
        result.modelChecksum(),
        json(result.baseline()),
        json(result.candidate()));
  }

  private static String json(Metrics metrics) {
    return String.format(
        Locale.ROOT,
        "{\"observations\":%d,\"accuracyPercent\":%.2f,\"falseJumps\":%d,"
            + "\"remoteJumps\":%d,\"meanRecoveryNotes\":%.2f,\"p95LatencyMs\":%.4f}",
        metrics.observations(),
        metrics.accuracyPercent(),
        metrics.falseJumps(),
        metrics.remoteJumps(),
        metrics.meanRecoveryNotes(),
        metrics.p95LatencyMs());
  }

  private static List<LearnedFollower.Sample> trace(int seed) {
    Random random = new Random(seed);
    int[] score = new int[32];
    for (int i = 0; i < score.length; i++) score[i] = 60 + random.nextInt(20);
    var samples = new ArrayList<LearnedFollower.Sample>();
    int position = 0;
    for (int i = 0; i < 28; i++) {
      if (i > 0) {
        double roll = random.nextDouble();
        if (roll < .08) {
          int next;
          do next = random.nextInt(score.length);
          while (Math.abs(next - position) <= 4);
          position = next;
        } else if (roll < .13) {
          // Repeated score note.
        } else if (roll < .25)
          position = Math.min(score.length - 1, position + 2 + random.nextInt(3));
        else position = Math.min(score.length - 1, position + 1);
      }
      int midi = score[position];
      if (random.nextDouble() < .12)
        midi = random.nextBoolean() ? Math.min(127, midi + 1) : Math.max(0, midi - 1);
      samples.add(new LearnedFollower.Sample(score, position, midi));
    }
    return List.copyOf(samples);
  }

  private static final class Collector {
    private final List<Long> latencies = new ArrayList<>();
    private int observations;
    private int correct;
    private int falseJumps;
    private int remoteJumps;
    private int recoveryNotes;

    void add(int[] truth, int[] predicted) {
      for (int i = 0; i < truth.length; i++) {
        observations++;
        if (truth[i] == predicted[i]) correct++;
        if (i == 0) continue;
        if (Math.abs(predicted[i] - predicted[i - 1]) > 4 && Math.abs(truth[i] - truth[i - 1]) <= 4)
          falseJumps++;
        if (Math.abs(truth[i] - truth[i - 1]) > 4) {
          remoteJumps++;
          int delay = 6;
          for (int look = i; look < Math.min(truth.length, i + 6); look++)
            if (predicted[look] == truth[look]) {
              delay = look - i;
              break;
            }
          recoveryNotes += delay;
        }
      }
    }

    Metrics metrics() {
      long[] ordered = latencies.stream().mapToLong(Long::longValue).toArray();
      Arrays.sort(ordered);
      double p95 = ordered[(int) Math.ceil(ordered.length * .95) - 1] / 1_000_000.0;
      return new Metrics(
          observations,
          100.0 * correct / observations,
          falseJumps,
          remoteJumps,
          (double) recoveryNotes / remoteJumps,
          p95);
    }
  }
}
