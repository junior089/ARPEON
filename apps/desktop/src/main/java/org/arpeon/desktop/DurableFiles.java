package org.arpeon.desktop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class DurableFiles {
  private DurableFiles() {}

  static void replace(Path target, byte[] bytes) throws IOException {
    Files.createDirectories(target.getParent());
    if (Files.isSymbolicLink(target) || Files.isSymbolicLink(target.getParent()))
      throw new IOException("Refusing to replace data through a symbolic link");
    Path temporary = Files.createTempFile(target.getParent(), ".write-", ".tmp");
    try {
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
