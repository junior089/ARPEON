package org.arpeon.audio;

import java.util.List;
import java.util.function.Consumer;

/** Owns exactly one capture session, with close cancelling pending device reads. */
public interface AudioInputPort extends AutoCloseable {
  List<AudioInputDevice> devices();

  void start(String deviceId, Consumer<AudioFrame> onFrame, Consumer<String> onFailure);

  void stop();

  long overruns();

  @Override
  void close();
}
