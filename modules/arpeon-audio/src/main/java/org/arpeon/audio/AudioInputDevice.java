package org.arpeon.audio;

import java.util.List;

public record AudioInputDevice(String id, String name, List<Integer> sampleRatesHz) {
  public AudioInputDevice {
    sampleRatesHz = List.copyOf(sampleRatesHz);
  }
}
