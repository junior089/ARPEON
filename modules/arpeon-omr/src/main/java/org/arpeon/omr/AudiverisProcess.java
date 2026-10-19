package org.arpeon.omr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Drains native output with a bounded diagnostic tail and stops only this process tree. */
final class AudiverisProcess {
  static final int MAX_DIAGNOSTIC_BYTES = 16 * 1024;

  private AudiverisProcess() {}

  static void run(List<String> command, Path directory, Duration timeout) throws IOException {
    Process process;
    try {
      var builder =
          new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
      // Each job has clean Audiveris settings, independent of any user-installed copy.
      if (System.getProperty("os.name").startsWith("Windows")) {
        builder.environment().put("APPDATA", directory.resolve("profile").toString());
      }
      String bundledHome = System.getProperty("arpeon.audiveris.home");
      if (bundledHome != null) {
        Path tessdata = Path.of(bundledHome).resolve("tessdata");
        if (java.nio.file.Files.isDirectory(tessdata))
          builder.environment().put("TESSDATA_PREFIX", tessdata.toString());
      }
      builder.environment().remove("JAVA_TOOL_OPTIONS");
      builder.environment().remove("_JAVA_OPTIONS");
      builder.environment().remove("JDK_JAVA_OPTIONS");
      process = builder.start();
    } catch (IOException failure) {
      throw new IOException(
          "Não foi possível iniciar o Audiveris. Confira a instalação e o caminho"
              + " configurado. Detalhe: "
              + failure.getMessage(),
          failure);
    }
    awaitCompletion(process, timeout);
  }

  static void awaitCompletion(Process process, Duration timeout) throws IOException {
    var tail = new Tail();
    Thread reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (InputStream stream = process.getInputStream()) {
                    byte[] bytes = new byte[4096];
                    int count;
                    while ((count = stream.read(bytes)) != -1) tail.append(bytes, count);
                  } catch (IOException ignored) {
                    // Stopping a native process can close its output pipe.
                  }
                });
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        stop(process);
        throw new IOException(
            "O Audiveris excedeu o limite de reconhecimento. Tente dividir o PDF"
                + " em arquivos menores.\n"
                + tail.text());
      }
      reader.join(2000);
      System.getLogger(AudiverisProcess.class.getName())
          .log(
              System.Logger.Level.INFO,
              "Audiveris exit {0}\n{1}",
              process.exitValue(),
              tail.text());
      if (process.exitValue() != 0)
        throw new IOException(
            "O Audiveris não concluiu o reconhecimento (código "
                + process.exitValue()
                + "). Confira o detalhe abaixo.\n"
                + tail.text());
    } catch (InterruptedException failure) {
      stop(process);
      Thread.currentThread().interrupt();
      throw new IOException("Reconhecimento cancelado.", failure);
    } finally {
      if (process.isAlive()) stop(process);
      reader.interrupt();
    }
  }

  private static void stop(Process process) {
    List<ProcessHandle> children = process.descendants().toList();
    for (ProcessHandle child : children.reversed()) child.destroyForcibly();
    process.destroyForcibly();
    boolean interrupted = Thread.interrupted();
    try {
      process.waitFor(5, TimeUnit.SECONDS);
      for (ProcessHandle child : children) {
        if (child.isAlive()) child.destroyForcibly();
      }
    } catch (InterruptedException failure) {
      interrupted = true;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  static final class Tail {
    private final byte[] bytes = new byte[MAX_DIAGNOSTIC_BYTES];
    private int length;

    synchronized void append(byte[] block, int count) {
      int kept = Math.min(count, bytes.length);
      int previous = Math.min(length, bytes.length - kept);
      System.arraycopy(bytes, length - previous, bytes, 0, previous);
      System.arraycopy(block, count - kept, bytes, previous, kept);
      length = previous + kept;
    }

    synchronized String text() {
      return new String(bytes, 0, length, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }
  }
}
