package org.arpeon.omr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudiverisProcessTest {
  @TempDir Path directory;

  private List<String> command(String mode) throws Exception {
    String launcherName =
        System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
            ? "java.exe"
            : "java";
    String classes =
        Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    return List.of(
        Path.of(System.getProperty("java.home"), "bin", launcherName).toString(),
        "-cp",
        classes,
        Probe.class.getName(),
        mode,
        directory.resolve("probe.pid").toString());
  }

  @Test
  void drainsLargeOutputAndReturnsBoundedFailureDetail() throws Exception {
    var failure =
        assertThrows(
            IOException.class,
            () -> AudiverisProcess.run(command("fail"), directory, Duration.ofSeconds(15)));
    assertTrue(failure.getMessage().contains("código 7"));
    assertTrue(failure.getMessage().contains("PROBE_FAILURE_DETAIL"));
    assertTrue(failure.getMessage().length() < AudiverisProcess.MAX_DIAGNOSTIC_BYTES + 300);
  }

  @Test
  void timeoutStopsNativeProcess() throws Exception {
    assertThrows(
        IOException.class,
        () -> AudiverisProcess.run(command("wait"), directory, Duration.ofSeconds(2)));
    long pid = Long.parseLong(Files.readString(directory.resolve("probe.pid")));
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }

  @Test
  void interruptionStopsNativeProcessAndPreservesInterruptFlag() throws Exception {
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    AudiverisProcess.run(command("wait"), directory, Duration.ofMinutes(1));
                  } catch (Throwable ex) {
                    failure.set(ex);
                    interrupted.set(Thread.currentThread().isInterrupted());
                  }
                });
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!Files.exists(directory.resolve("probe.pid")) && System.nanoTime() < deadline)
      Thread.sleep(10);
    assertTrue(Files.exists(directory.resolve("probe.pid")));
    worker.interrupt();
    worker.join(7000);
    assertFalse(worker.isAlive());
    assertInstanceOf(IOException.class, failure.get());
    assertTrue(interrupted.get());
    long pid = Long.parseLong(Files.readString(directory.resolve("probe.pid")));
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      Path marker = Path.of(args[1]);
      Path ready = marker.resolveSibling(marker.getFileName() + ".ready");
      Files.writeString(ready, Long.toString(ProcessHandle.current().pid()));
      Files.move(ready, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      if (args[0].equals("fail")) {
        System.err.println("x".repeat(65536));
        System.err.println("PROBE_FAILURE_DETAIL");
        System.exit(7);
      }
      Thread.sleep(60000);
    }
  }
}
