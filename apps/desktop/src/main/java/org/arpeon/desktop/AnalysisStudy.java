package org.arpeon.desktop;

import java.util.List;
import org.arpeon.analysis.AnalysisConfig;

/** Musical locations and criteria snapshotted with a report, including repeated passages. */
final class AnalysisStudy {
  record Location(
      int index,
      int measureIndex,
      String measureNumber,
      int eventIndex,
      int executionMeasureIndex,
      int endMeasureIndex) {}

  record Context(
      String scoreXmlSha256,
      double initialBpm,
      int soundingSemitones,
      AnalysisConfig config,
      List<Location> notes) {}

  private AnalysisStudy() {}
}
