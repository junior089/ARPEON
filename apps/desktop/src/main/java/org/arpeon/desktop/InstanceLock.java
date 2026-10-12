package org.arpeon.desktop;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** OS-owned locks are released even after a crash; stale PID files cannot prevent startup. */
final class InstanceLock implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  private InstanceLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  static InstanceLock acquire(Path directory) throws IOException {
    FileChannel channel =
        FileChannel.open(
            directory.resolve("instance.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) throw new IOException("O ARPEON já está aberto neste perfil.");
      return new InstanceLock(channel, lock);
    } catch (IOException | OverlappingFileLockException failure) {
      channel.close();
      throw new IOException(
          "Não foi possível abrir o perfil. O ARPEON pode já estar aberto.", failure);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      channel.close();
    }
  }
}
