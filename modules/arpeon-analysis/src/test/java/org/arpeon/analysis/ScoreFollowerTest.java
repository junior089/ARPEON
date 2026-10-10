package org.arpeon.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreFollowerTest {
  private static final List<ExpectedEvent> SCORE =
      List.of(
          new ExpectedEvent(0, 60, 0, 400),
          new ExpectedEvent(1, 62, 500, 400),
          new ExpectedEvent(2, 64, 1000, 400),
          new ExpectedEvent(3, 65, 1500, 400),
          new ExpectedEvent(4, 67, 2000, 400),
          new ExpectedEvent(5, 69, 2500, 400),
          new ExpectedEvent(6, 71, 3000, 400),
          new ExpectedEvent(7, 72, 3500, 400));

  @Test
  void followsPitchAtVariableTempoAndHoldsDuringSilence() {
    var follower = new ScoreFollower(SCORE);
    follower.accept(1000, 60, .9);
    assertEquals(-1, follower.position().noteIndex());
    follower.accept(1100, 60, .9);
    assertEquals(0, follower.position().noteIndex());
    follower.accept(6000, null, 0);
    assertEquals(0, follower.position().noteIndex());
    follower.accept(8000, 62, .9);
    follower.accept(8100, 62, .9);
    assertEquals(1, follower.position().noteIndex());
  }

  @Test
  void skipsNearbyMissedNotesAndRequiresTwoNotesForRemoteRecovery() {
    var follower = new ScoreFollower(SCORE);
    follower.accept(1000, 60, .9);
    follower.accept(1100, 60, .9);
    follower.accept(1200, 65, .9);
    follower.accept(1300, 65, .9);
    assertEquals(3, follower.position().noteIndex());
    follower.accept(1400, 80, .9);
    follower.accept(1500, 80, .9);
    assertEquals(3, follower.position().noteIndex());
    assertEquals("UNCERTAIN", follower.position().state());
    follower.accept(1600, 60, .9);
    follower.accept(1700, 60, .9);
    assertEquals(3, follower.position().noteIndex());
  }

  @Test
  void recoversAfterConfirmedRemoteSequence() {
    var follower = new ScoreFollower(SCORE);
    follower.accept(1000, 60, .9);
    follower.accept(1100, 60, .9);
    follower.accept(1200, 69, .9);
    follower.accept(1300, 69, .9);
    assertEquals(0, follower.position().noteIndex());
    follower.accept(1400, 71, .9);
    follower.accept(1500, 71, .9);
    assertEquals(6, follower.position().noteIndex());
    assertEquals("RECOVERED", follower.position().state());
  }

  @Test
  void weakWindowsAndDuplicateTimestampsDoNotAdvance() {
    var follower = new ScoreFollower(SCORE);
    follower.accept(1000, 60, .9);
    follower.accept(1000, 60, .9);
    follower.accept(1100, 60, .5);
    follower.accept(1200, 60, .9);
    assertEquals(-1, follower.position().noteIndex());
    follower.accept(1300, 60, .9);
    assertEquals(0, follower.position().noteIndex());
  }

  @Test
  void estimatesLocalTempoFromConfirmedConsecutiveNotesWithoutChangingPitchCursorRules() {
    var follower = new ScoreFollower(SCORE);
    confirm(follower, 1_000, 60);
    confirm(follower, 1_800, 62);
    confirm(follower, 2_600, 64);
    confirm(follower, 3_400, 65);

    var position = follower.position();
    assertEquals(3, position.noteIndex());
    assertEquals(1.6, position.tempoRatio(), .01);
    assertEquals(3, position.tempoSamples());
    assertEquals("ESTIMATING", position.tempoState());
  }

  @Test
  void marksTempoHypothesisUncertainForOutlierIntervalsWhileRetainingPitchMatch() {
    var follower = new ScoreFollower(SCORE);
    confirm(follower, 1_000, 60);
    confirm(follower, 1_200, 62);

    assertEquals(1, follower.position().noteIndex());
    assertEquals(1, follower.position().tempoRatio());
    assertEquals(0, follower.position().tempoSamples());
    assertEquals("UNCERTAIN", follower.position().tempoState());
  }

  @Test
  void requiresThreeNewConsecutiveIntervalsAfterAnOutlier() {
    var follower = new ScoreFollower(SCORE);
    confirm(follower, 1_000, 60);
    confirm(follower, 1_800, 62);
    confirm(follower, 2_000, 64);
    assertEquals(0, follower.position().tempoSamples());
    assertEquals("UNCERTAIN", follower.position().tempoState());

    confirm(follower, 2_900, 65);
    confirm(follower, 3_700, 67);
    assertEquals(2, follower.position().tempoSamples());
    assertEquals("UNCERTAIN", follower.position().tempoState());
    confirm(follower, 4_500, 69);
    assertEquals(3, follower.position().tempoSamples());
    assertEquals("ESTIMATING", follower.position().tempoState());
  }

  private static void confirm(ScoreFollower follower, long timestamp, int midi) {
    follower.accept(timestamp, midi, .9);
    follower.accept(timestamp + 100, midi, .9);
  }
}
