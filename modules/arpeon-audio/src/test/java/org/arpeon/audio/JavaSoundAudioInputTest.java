package org.arpeon.audio;

import static org.junit.jupiter.api.Assertions.*;

import javax.sound.sampled.Mixer;
import org.junit.jupiter.api.Test;

class JavaSoundAudioInputTest {
  private static Mixer.Info info(String name, String vendor) {
    return new Mixer.Info(name, vendor, "Input", "1") {};
  }

  @Test
  void identitySurvivesDiscoveryAndUnrelatedDeviceInsertions() {
    var original = JavaSoundAudioInput.deviceId(info("USB microphone", "Vendor"), 0);
    JavaSoundAudioInput.deviceId(info("Other microphone", "Vendor"), 0);
    assertEquals(original, JavaSoundAudioInput.deviceId(info("USB microphone", "Vendor"), 0));
    assertNotEquals(
        original, JavaSoundAudioInput.deviceId(info("USB microphone", "Other vendor"), 0));
    assertNotEquals(original, JavaSoundAudioInput.deviceId(info("USB microphone", "Vendor"), 1));
  }
}
