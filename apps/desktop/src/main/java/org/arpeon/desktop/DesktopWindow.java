package org.arpeon.desktop;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefJSDialogCallback;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.cef.handler.CefJSDialogHandler.JSDialogType;
import org.cef.handler.CefJSDialogHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;

/** The existing same-origin UI runs inside the shipped Chromium engine. No remote Java bridge. */
final class DesktopWindow implements AutoCloseable {
  private final CefApp app;
  private final CefClient client;
  private final JFrame frame = new JFrame("ARPEON");
  private final CefBrowser browser;
  private volatile boolean loaded;

  DesktopWindow(URI launch, Runnable closeBeforeLoad) throws Exception {
    this(launch, closeBeforeLoad, () -> launch);
  }

  DesktopWindow(URI launch, Runnable closeBeforeLoad, java.util.function.Supplier<URI> activeLaunch)
      throws Exception {
    var builder = new CefAppBuilder();
    var bundle = AppInstallation.component("tools/cef");
    if (!java.nio.file.Files.isRegularFile(bundle.resolve("install.lock")))
      throw new IOException("Chromium não está íntegro neste pacote. Reinstale o ARPEON.");
    builder.setInstallDir(bundle.toFile());
    builder.setSkipInstallation(true);
    builder.setMirrors(List.of());
    builder.addJcefArgs(
        "--disable-extensions", "--disable-component-update", "--disable-background-networking");
    builder.getCefSettings().windowless_rendering_enabled = false;
    builder.getCefSettings().root_cache_path = UserDirectories.data().resolve("browser").toString();
    builder.getCefSettings().cache_path =
        UserDirectories.data().resolve("browser/default").toString();
    builder.getCefSettings().log_file =
        UserDirectories.data().resolve("logs/chromium.log").toString();
    app = builder.build();
    client = app.createClient();
    java.util.function.Supplier<String> currentOrigin =
        () -> {
          URI active = activeLaunch.get();
          return active.getScheme() + "://" + active.getAuthority();
        };
    client.addRequestHandler(
        new CefRequestHandlerAdapter() {
          @Override
          public boolean onBeforeBrowse(
              CefBrowser view,
              CefFrame page,
              CefRequest request,
              boolean userGesture,
              boolean redirect) {
            String url = request.getURL();
            String origin = currentOrigin.get();
            if (url.equals("about:blank")
                || url.startsWith(origin + "/#")
                || url.equals(origin + "/")) {
              if (page.isMain()) loaded = false;
              return false;
            }
            if (userGesture) openLink(url, origin);
            return true;
          }
        });
    client.addLifeSpanHandler(
        new CefLifeSpanHandlerAdapter() {
          @Override
          public boolean onBeforePopup(CefBrowser view, CefFrame page, String url, String name) {
            openLink(url, currentOrigin.get());
            return true;
          }
        });
    client.addDownloadHandler(
        new CefDownloadHandlerAdapter() {
          @Override
          public boolean onBeforeDownload(
              CefBrowser view,
              CefDownloadItem item,
              String name,
              CefBeforeDownloadCallback callback) {
            String url = item.getURL();
            String origin = currentOrigin.get();
            if (!(url.startsWith(origin + "/") || url.startsWith("blob:" + origin + "/")))
              return true;
            callback.Continue("", true);
            return true;
          }
        });
    client.addJSDialogHandler(
        new CefJSDialogHandlerAdapter() {
          @Override
          public boolean onJSDialog(
              CefBrowser view,
              String url,
              JSDialogType type,
              String text,
              String defaultPrompt,
              CefJSDialogCallback callback,
              BoolRef suppress) {
            SwingUtilities.invokeLater(
                () -> {
                  boolean accepted;
                  if (type == JSDialogType.JSDIALOGTYPE_ALERT) {
                    JOptionPane.showMessageDialog(
                        frame, text, "ARPEON", JOptionPane.INFORMATION_MESSAGE);
                    accepted = true;
                  } else {
                    accepted =
                        JOptionPane.showConfirmDialog(
                                frame,
                                text,
                                "ARPEON",
                                JOptionPane.OK_CANCEL_OPTION,
                                JOptionPane.QUESTION_MESSAGE)
                            == JOptionPane.OK_OPTION;
                  }
                  callback.Continue(accepted, "");
                });
            return true;
          }
        });
    client.addLoadHandler(
        new CefLoadHandlerAdapter() {
          @Override
          public void onLoadEnd(CefBrowser view, CefFrame page, int status) {
            if (page.isMain()) loaded = status == 200;
          }
        });
    browser = client.createBrowser(launch.toString(), false, false);
    SwingUtilities.invokeAndWait(
        () -> {
          frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
          frame.setMinimumSize(new Dimension(800, 560));
          frame.setSize(1280, 840);
          frame.setLocationRelativeTo(null);
          frame.setLayout(new BorderLayout());
          frame.add(browser.getUIComponent(), BorderLayout.CENTER);
          try (var icon = DesktopWindow.class.getResourceAsStream("/web/brand/arpeon-256.png")) {
            if (icon != null) frame.setIconImage(ImageIO.read(icon));
          } catch (IOException failure) {
            System.getLogger(DesktopWindow.class.getName())
                .log(System.Logger.Level.WARNING, "Application icon could not be read", failure);
          }
          frame.addWindowListener(
              new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                  requestClose(closeBeforeLoad);
                }
              });
          frame.setVisible(true);
        });
  }

  void requestClose(Runnable closeBeforeLoad) {
    if (!loaded) closeBeforeLoad.run();
    else
      browser.executeJavaScript(
          "window.dispatchEvent(new Event('arpeon-close'));", browser.getURL(), 0);
  }

  java.util.concurrent.CompletableFuture<String> document() {
    var result = new java.util.concurrent.CompletableFuture<String>();
    browser.getSource(result::complete);
    return result;
  }

  void execute(String script) {
    browser.executeJavaScript(script, browser.getURL(), 0);
  }

  void resize(int width, int height) throws Exception {
    SwingUtilities.invokeAndWait(() -> frame.setSize(width, height));
  }

  void capture(java.nio.file.Path target) throws Exception {
    String screenshot =
        browser
            .getDevToolsClient()
            .executeDevToolsMethod(
                "Page.captureScreenshot",
                "{\"format\":\"png\",\"fromSurface\":true,\"captureBeyondViewport\":false}")
            .get(15, java.util.concurrent.TimeUnit.SECONDS);
    var result = com.google.gson.JsonParser.parseString(screenshot).getAsJsonObject();
    if (result.has("result")) result = result.getAsJsonObject("result");
    java.nio.file.Files.write(
        target, java.util.Base64.getDecoder().decode(result.get("data").getAsString()));
  }

  private void openLink(String value, String origin) {
    try {
      URI uri = URI.create(value);
      boolean local = value.startsWith(origin + "/") && uri.getPath().endsWith(".txt");
      if ((local
              || ("https".equals(uri.getScheme())
                  && uri.getHost() != null
                  && uri.getUserInfo() == null))
          && Desktop.isDesktopSupported()) Desktop.getDesktop().browse(uri);
    } catch (IOException | IllegalArgumentException failure) {
      System.getLogger(DesktopWindow.class.getName())
          .log(System.Logger.Level.WARNING, "Could not open reference link", failure);
    }
  }

  @Override
  public void close() {
    browser.close(true);
    client.dispose();
    app.dispose();
    SwingUtilities.invokeLater(frame::dispose);
  }
}
