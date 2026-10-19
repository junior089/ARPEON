package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoundBankStoreTest {
  @TempDir Path directory;

  @Test
  void rejectsInvalidBanksAndPathNamesBeforeWritingAssets() throws Exception {
    var store = new SoundBankStore(directory);
    assertTrue(store.list().isEmpty());
    assertThrows(IOException.class, () -> store.importBank(new byte[] {1, 2, 3}, "broken.sf2"));
    assertThrows(
        IllegalArgumentException.class, () -> store.importBank(new byte[] {1}, "../bank.sf2"));
    assertTrue(store.list().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> store.load("../bank"));
  }

  @Test
  void detectsModifiedContentInsteadOfLoadingAnUnverifiedBank() throws Exception {
    String id = "a".repeat(64);
    Files.writeString(
        directory.resolve(id + ".json"),
        "{\"id\":\""
            + id
            + "\",\"name\":\"Test\",\"filename\":\"test.sf2\",\"bytes\":3,\"programs\":1}");
    Files.write(directory.resolve(id + ".bank"), new byte[] {1, 2, 3});
    var failure = assertThrows(IOException.class, () -> new SoundBankStore(directory).load(id));
    assertTrue(failure.getMessage().contains("integridade"));
    var store = new SoundBankStore(directory);
    store.delete(id);
    assertTrue(store.list().isEmpty());
  }
}
