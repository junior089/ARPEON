package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreferencesStoreTest {
  @TempDir Path temporary;

  static JsonObject settings() {
    return JsonParser.parseString(
            """
            {"schemaVersion":1,"theme":"light","explorer":false,"inspector":false,
             "inspectorWidth":360,"zoom":125,"mode":"study","scoreDark":true}
            """)
        .getAsJsonObject();
  }

  @Test
  void survivesNewStoreAndReplacesSettingsWithoutLeavingTemporaryFiles() throws Exception {
    Path file = temporary.resolve("profile/preferences.json");
    var store = new PreferencesStore(file);
    assertNull(store.load());
    var saved = store.save(settings());
    assertEquals(saved, new PreferencesStore(file).load());
    var changed = settings();
    changed.addProperty("zoom", 150);
    store.save(changed);
    assertEquals(150, new PreferencesStore(file).load().zoom());
    try (var files = Files.list(file.getParent())) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void rejectsInvalidWritesWithoutReplacingTheLastGoodFile() throws Exception {
    Path file = temporary.resolve("preferences.json");
    var store = new PreferencesStore(file);
    var original = store.save(settings());
    for (String field : new String[] {"zoom", "schemaVersion", "mode", "explorer"}) {
      var invalid = settings();
      invalid.addProperty(field, "invalid");
      assertThrows(IllegalArgumentException.class, () -> store.save(invalid));
    }
    var fractional = settings();
    fractional.addProperty("zoom", 125.5);
    assertThrows(ArithmeticException.class, () -> store.save(fractional));
    var extra = settings();
    extra.addProperty("microphoneEnabled", true);
    assertThrows(IllegalArgumentException.class, () -> store.save(extra));
    assertEquals(original, store.load());
  }

  @Test
  void preservesReadingAssistanceAndUpgradesLegacySettings() throws Exception {
    var store = new PreferencesStore(temporary.resolve("reading.json"));
    var legacy = store.save(settings());
    assertFalse(legacy.readingEnabled());
    assertEquals("letters", legacy.readingLanguage());
    var updated = settings();
    updated.addProperty("readingEnabled", true);
    updated.addProperty("readingLanguage", "portuguese");
    updated.addProperty("readingOctave", true);
    updated.addProperty("readingScope", "selection");
    updated.addProperty("followFingering", true);
    store.save(updated);
    var restored = store.load();
    assertTrue(restored.readingEnabled());
    assertTrue(restored.followFingering());
    assertEquals("portuguese", restored.readingLanguage());
    updated.addProperty("readingLanguage", "invalid");
    assertThrows(IllegalArgumentException.class, () -> store.save(updated));
    assertEquals(restored, store.load());
  }

  @Test
  void ignoresCorruptUnsupportedAndOversizedFilesAndReportsWriteFailures() throws Exception {
    Path file = temporary.resolve("preferences.json");
    var store = new PreferencesStore(file);
    for (String content : new String[] {"{broken", "null", "[]", "{}", " ".repeat(4097)}) {
      Files.writeString(file, content);
      assertNull(store.load());
    }
    Path blocked = temporary.resolve("not-a-directory");
    Files.writeString(blocked, "file");
    assertThrows(
        IOException.class,
        () -> new PreferencesStore(blocked.resolve("prefs.json")).save(settings()));
  }
}
