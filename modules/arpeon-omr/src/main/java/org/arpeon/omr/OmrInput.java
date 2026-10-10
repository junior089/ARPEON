package org.arpeon.omr;

import java.util.Objects;

public record OmrInput(byte[] bytes, String filename) {
  public OmrInput {
    bytes = Objects.requireNonNull(bytes).clone();
    Objects.requireNonNull(filename);
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }
}
