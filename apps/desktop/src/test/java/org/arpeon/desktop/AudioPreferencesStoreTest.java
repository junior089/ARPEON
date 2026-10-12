package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioPreferencesStoreTest {
  @TempDir Path temporary;

  static JsonObject settings() {
    return JsonParser.parseString(
            """
            {"schemaVersion":1,"deviceId":"stable-id","deviceName":"Microfone USB","referenceHz":442.1}
            """)
        .getAsJsonObject();
  }

  @Test
  void restoresAfterRestartWithoutCapturePermission() throws Exception {
    Path file = temporary.resolve("profile/audio-preferences.json");
    var store = new AudioPreferencesStore(file);
    assertNull(store.load());
    var saved = store.save(settings());
    assertEquals(saved, new AudioPreferencesStore(file).load());
    try (var files = Files.list(file.getParent())) {
      assertEquals(1, files.count());
    }
    assertFalse(Files.readString(file).contains("consent"));
  }

  @Test
  void rejectsInvalidWritesAndPreservesGoodSettings() throws Exception {
    var store = new AudioPreferencesStore(temporary.resolve("audio.json"));
    var saved = store.save(settings());
    for (double hz : new double[] {399.9, 480.1, Double.NaN}) {
      var invalid = settings();
      invalid.addProperty("referenceHz", hz);
      assertThrows(IllegalArgumentException.class, () -> store.save(invalid));
    }
    var extra = settings();
    extra.addProperty("consent", true);
    assertThrows(IllegalArgumentException.class, () -> store.save(extra));
    assertEquals(saved, store.load());
  }

  @Test
  void reportsCorruptionWithoutDeletingItAndRecoversWithExplicitSave() throws Exception {
    Path file = temporary.resolve("audio.json");
    var store = new AudioPreferencesStore(file);
    for (String content : new String[] {"{broken", "null", "{}", " ".repeat(4097)}) {
      Files.writeString(file, content);
      assertThrows(IOException.class, store::load);
      assertEquals(content, Files.readString(file));
    }
    store.save(settings());
    assertEquals(442.1, store.load().referenceHz());
  }
}
