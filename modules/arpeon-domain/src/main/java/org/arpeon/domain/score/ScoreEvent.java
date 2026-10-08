package org.arpeon.domain.score;

import org.arpeon.domain.time.Duration;

public sealed interface ScoreEvent permits Note, Rest {
  Duration duration();
}
