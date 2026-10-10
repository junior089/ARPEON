package org.arpeon.ml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Experimental score-position model trained from labeled synthetic transitions. Never used by the
 * app.
 */
public final class LearnedFollower {
  public record Sample(int[] score, int position, int midi) {}

  private final double[] transition;
  private final double[] emission;
  private final String checksum;
  private double[] previous;
  private int[] score;
  private int[][] transitionMembers;
  private int position = -1;

  private LearnedFollower(double[] transition, double[] emission) {
    this.transition = transition;
    this.emission = emission;
    String serialized = Arrays.toString(transition) + Arrays.toString(emission);
    try {
      checksum =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(serialized.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  public static LearnedFollower train(List<List<Sample>> traces) {
    double[] transitions = {1, 1, 1, 1};
    double[] emissions = {1, 1, 1};
    for (var trace : traces) {
      int previous = -1;
      for (Sample sample : trace) {
        if (sample.position() < 0 || sample.position() >= sample.score().length)
          throw new IllegalArgumentException("Invalid training position");
        emissions[emissionClass(sample.score()[sample.position()], sample.midi())]++;
        if (previous >= 0) transitions[transitionClass(previous, sample.position())]++;
        previous = sample.position();
      }
    }
    normalize(transitions);
    normalize(emissions);
    return new LearnedFollower(transitions, emissions);
  }

  public String checksum() {
    return checksum;
  }

  public void start(int[] score) {
    if (score == null || score.length == 0 || score.length > 256)
      throw new IllegalArgumentException("Expected 1–256 score notes for experimental evaluation");
    this.score = score.clone();
    transitionMembers = new int[score.length][4];
    for (int from = 0; from < score.length; from++)
      for (int target = 0; target < score.length; target++)
        transitionMembers[from][transitionClass(from, target)]++;
    previous = null;
    position = -1;
  }

  public int observe(int midi) {
    if (score == null || midi < 0 || midi > 127)
      throw new IllegalArgumentException("Start a score and provide a MIDI pitch");
    double[] next = new double[score.length];
    for (int target = 0; target < score.length; target++) {
      double best;
      if (previous == null) {
        best = target == 0 ? Math.log(.8) : Math.log(.2 / Math.max(1, score.length - 1));
      } else {
        best = Double.NEGATIVE_INFINITY;
        for (int from = 0; from < score.length; from++) {
          int category = transitionClass(from, target);
          int members = transitionMembers[from][category];
          double candidate = previous[from] + Math.log(transition[category] / members);
          if (candidate > best) best = candidate;
        }
      }
      int category = emissionClass(score[target], midi);
      int members = category == 0 ? 1 : category == 1 ? 2 : 125;
      next[target] = best + Math.log(emission[category] / members);
    }
    int bestIndex = 0;
    for (int i = 1; i < next.length; i++) if (next[i] > next[bestIndex]) bestIndex = i;
    double reference = next[bestIndex];
    for (int i = 0; i < next.length; i++) next[i] -= reference;
    previous = next;
    position = bestIndex;
    return position;
  }

  private static int transitionClass(int from, int to) {
    int delta = to - from;
    if (delta == 0) return 0;
    if (delta == 1) return 1;
    if (delta >= 2 && delta <= 4) return 2;
    return 3;
  }

  private static int emissionClass(int expected, int played) {
    int delta = Math.abs(expected - played);
    return delta == 0 ? 0 : delta == 1 ? 1 : 2;
  }

  private static void normalize(double[] values) {
    double sum = Arrays.stream(values).sum();
    for (int i = 0; i < values.length; i++) values[i] /= sum;
  }
}
