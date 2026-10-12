package org.arpeon.desktop;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import javax.swing.JOptionPane;
import org.arpeon.audio.JavaSoundAudioInput;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.arpeon.tuner.TunerService;

public final class DesktopApplication {
  private DesktopApplication() {}

  public static void main(String[] args) {
    // Recognition runs on a worker and can take several minutes.
    System.setProperty("sun.net.httpserver.maxReqTime", "120");
    System.setProperty("sun.net.httpserver.maxRspTime", "900");
    try {
      if (UserDirectories.data().startsWith(AppInstallation.root()))
        throw new java.io.IOException(
            "A pasta de dados coincide com a instalação. Instale ou extraia o ARPEON em outra pasta"
                + " para preservar seus projetos.");
      UserDirectories.ensureData();
      try (var instance = InstanceLock.acquire(UserDirectories.data())) {
        java.util.Objects.requireNonNull(instance);
        var log = ApplicationLog.open();
        try {
          System.setProperty(
              "arpeon.audiveris.home",
              AppInstallation.component("tools/audiveris-5.11.0").toString());
          System.setProperty(
              "arpeon.omr.tempDir", UserDirectories.data().resolve("temporary").toString());
          var stopped = new CountDownLatch(1);
          try (var server =
              new DesktopServer(
                  new TunerService(new JavaSoundAudioInput(), new YinPitchDetector()))) {
            server.onShutdown(stopped::countDown);
            Thread hook = new Thread(server::close, "arpeon-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
              server.start();
              try (var window = new DesktopWindow(server.launchUri(), stopped::countDown)) {
                var settings = updateSettings();
                String version = AppInstallation.version();
                try (var updater =
                    new WindowsUpdater(
                        settings,
                        version,
                        server::canUpdate,
                        () -> window.requestClose(stopped::countDown))) {
                  try {
                    updater.initialize(
                        AppInstallation.root().resolve("tools/updater/WinSparkle.dll"));
                  } catch (java.io.IOException failure) {
                    System.getLogger(DesktopApplication.class.getName())
                        .log(
                            System.Logger.Level.ERROR,
                            "Update initialization failed; application remains available",
                            failure);
                  }
                  server.attachUpdater(updater);
                  stopped.await();
                }
              }
            } finally {
              Runtime.getRuntime().removeShutdownHook(hook);
            }
          }
        } finally {
          log.close();
        }
      }
      System.exit(0);
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      System.getLogger(DesktopApplication.class.getName())
          .log(System.Logger.Level.ERROR, "Application failed", failure);
      if (!GraphicsEnvironment.isHeadless())
        JOptionPane.showMessageDialog(
            null,
            "Não foi possível abrir o ARPEON.\n" + failure.getMessage(),
            "ARPEON",
            JOptionPane.ERROR_MESSAGE);
      System.exit(1);
    }
  }

  private static UpdateSettings updateSettings() {
    try {
      return UpdateSettings.load(AppInstallation.root().resolve("config/updates.properties"));
    } catch (java.io.IOException failure) {
      System.getLogger(DesktopApplication.class.getName())
          .log(
              System.Logger.Level.ERROR,
              "Unsafe update configuration; updates disabled, application remains available",
              failure);
      return new UpdateSettings(false, "stable", "", "");
    }
  }
}
