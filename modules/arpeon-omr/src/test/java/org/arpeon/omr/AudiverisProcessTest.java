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
        mode);
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
    Process process = new ProcessBuilder(command("wait")).directory(directory.toFile()).start();
    try {
      var failure =
          assertThrows(
              IOException.class,
              () -> AudiverisProcess.awaitCompletion(process, Duration.ofSeconds(2)));
      assertTrue(failure.getMessage().contains("excedeu o limite"));
      assertFalse(process.isAlive());
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void interruptionStopsNativeProcessAndPreservesInterruptFlag() throws Exception {
    Process process = new ProcessBuilder(command("wait")).directory(directory.toFile()).start();
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    AudiverisProcess.awaitCompletion(process, Duration.ofMinutes(1));
                  } catch (Throwable ex) {
                    failure.set(ex);
                    interrupted.set(Thread.currentThread().isInterrupted());
                  }
                });
    try {
      worker.interrupt();
      worker.join(7000);
      assertFalse(worker.isAlive());
      assertInstanceOf(IOException.class, failure.get());
      assertTrue(interrupted.get());
      assertFalse(process.isAlive());
    } finally {
      worker.interrupt();
      process.destroyForcibly();
    }
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      if (args[0].equals("fail")) {
        System.err.println("x".repeat(65536));
        System.err.println("PROBE_FAILURE_DETAIL");
        System.exit(7);
      }
      Thread.sleep(60000);
    }
  }
}
