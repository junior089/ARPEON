package org.arpeon.omr;

import java.io.IOException;

public interface OmrEngine {
  default boolean available() {
    return true;
  }

  OmrResult recognize(OmrInput input) throws IOException;

  default OmrResult recognize(OmrInput input, java.util.function.Consumer<String> phase)
      throws IOException {
    phase.accept("recognizing");
    return recognize(input);
  }
}
