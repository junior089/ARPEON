package org.arpeon.dsp.pitch;

import org.arpeon.audio.AudioFrame;

public interface PitchDetector {
  PitchDetection detect(AudioFrame frame);
}
