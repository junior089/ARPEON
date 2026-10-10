package org.arpeon.desktop;

import java.awt.Desktop;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import org.arpeon.audio.JavaSoundAudioInput;
import org.arpeon.dsp.pitch.YinPitchDetector;
import org.arpeon.tuner.TunerService;

public final class DesktopApplication {
  private DesktopApplication() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    // Uploads can be large; Audiveris recognition and first-time setup can take several minutes.
    System.setProperty("sun.net.httpserver.maxReqTime", "120");
    System.setProperty("sun.net.httpserver.maxRspTime", "900");
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
      throw new IllegalStateException("ARPEON requires a desktop session with a default browser");
    var stopped = new CountDownLatch(1);
    var server =
        new DesktopServer(new TunerService(new JavaSoundAudioInput(), new YinPitchDetector()));
    server.onShutdown(stopped::countDown);
    Runtime.getRuntime().addShutdownHook(new Thread(server::close, "arpeon-shutdown"));
    server.start();
    try {
      Desktop.getDesktop().browse(server.launchUri());
    } catch (IOException failure) {
      server.close();
      throw failure;
    }
    System.getLogger(DesktopApplication.class.getName())
        .log(System.Logger.Level.INFO, "ARPEON opened locally. Stop the launcher to exit.");
    stopped.await();
  }
}
