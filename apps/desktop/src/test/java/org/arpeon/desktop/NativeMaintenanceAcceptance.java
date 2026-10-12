package org.arpeon.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.arpeon.audio.JavaSoundAudioInput;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.arpeon.tuner.TunerService;

/** Visual acceptance of the shipped settings/update surface, using the packaged frontend. */
public final class NativeMaintenanceAcceptance {
  private NativeMaintenanceAcceptance() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException("Expected package, profile and results");
    Path root = Path.of(args[0]).toAbsolutePath();
    Path output = Files.createDirectories(Path.of(args[2]).toAbsolutePath());
    System.setProperty("arpeon.installDir", root.toString());
    System.setProperty("arpeon.dataDir", Path.of(args[1]).toAbsolutePath().toString());
    UserDirectories.ensureData();
    try (var server =
            new DesktopServer(new TunerService(new JavaSoundAudioInput(), new YinPitchDetector()));
        var updater =
            new WindowsUpdater(
                UpdateSettings.load(root.resolve("config/updates.properties")),
                com.google.gson.JsonParser.parseString(
                        Files.readString(root.resolve("BUILD-INFO.json")))
                    .getAsJsonObject()
                    .get("version")
                    .getAsString(),
                () -> false,
                () -> {})) {
      updater.initialize(root.resolve("tools/updater/WinSparkle.dll"));
      server.attachUpdater(updater);
      server.start();
      try (var window = new DesktopWindow(server.launchUri(), () -> {})) {
        await(window, "Preferências");
        window.execute("document.querySelector('.settings-button').click();");
        await(window, "Gerenciar atualizações");
        window.execute(
            "Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='Gerenciar"
                + " atualizações').click();");
        await(window, "Atualizações indisponíveis neste pacote");
        Thread.sleep(300);
        window.capture(output.resolve("updates-1280.png"));
        window.resize(800, 600);
        Thread.sleep(300);
        window.capture(output.resolve("updates-800.png"));
        window.execute("document.querySelector('.product-dialog .dialog-heading button').click();");
      }
    }
    System.out.println(
        "Packaged update/settings surface captured. Inactive feed; no network request.");
    System.exit(0);
  }

  private static void await(DesktopWindow window, String text) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    do {
      try {
        if (window.document().get(5, TimeUnit.SECONDS).contains(text)) return;
      } catch (java.util.concurrent.TimeoutException initializing) {
        continue;
      }
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Packaged UI did not show: " + text);
  }
}
