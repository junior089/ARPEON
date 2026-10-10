package org.arpeon.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FollowerEvaluationTest {
  @Test
  void trainsOnlyOnSeparateSyntheticTracesAndReportsBothAlgorithms() {
    var result = FollowerEvaluation.run();
    assertEquals(64, result.modelChecksum().length());
    assertEquals(2800, result.baseline().observations());
    assertEquals(result.baseline().observations(), result.candidate().observations());
    assertTrue(result.baseline().remoteJumps() > 0);
    assertEquals(result.baseline().remoteJumps(), result.candidate().remoteJumps());
    assertTrue(result.baseline().accuracyPercent() >= 0);
    assertTrue(result.candidate().accuracyPercent() >= 0);
  }
}
