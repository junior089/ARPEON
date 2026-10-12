package org.arpeon.desktop;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Thin binding to WinSparkle's signed installer protocol; no custom downloader or installer. */
@SuppressWarnings(
    "restricted") // Reviewed C signatures from the pinned WinSparkle header; launcher enables
// native access.
final class WindowsUpdater implements AutoCloseable {
  record Status(
      boolean enabled,
      String channel,
      String version,
      String message,
      boolean automaticChecking,
      String state,
      String lastCheckedAt) {}

  private final UpdateSettings settings;
  private final String version;
  private final BooleanSupplier canShutdown;
  private final Runnable shutdown;
  private final UpdatePreferences preferences;
  private volatile String state = "idle";
  private volatile String lastCheckedAt = "";
  private boolean automaticChecking;
  private Arena arena;
  private SymbolLookup library;

  WindowsUpdater(
      UpdateSettings settings, String version, BooleanSupplier canShutdown, Runnable shutdown) {
    this(
        settings,
        version,
        canShutdown,
        shutdown,
        new UpdatePreferences(
            UserDirectories.data().resolve("update-preferences-" + settings.channel() + ".txt")));
  }

  WindowsUpdater(
      UpdateSettings settings,
      String version,
      BooleanSupplier canShutdown,
      Runnable shutdown,
      UpdatePreferences preferences) {
    this.settings = settings;
    this.version = version;
    this.canShutdown = canShutdown;
    this.shutdown = shutdown;
    this.preferences = preferences;
  }

  synchronized void initialize(Path dll) throws IOException {
    if (!settings.enabled()) return;
    if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
      throw new IOException("Atualização integrada ainda não está validada neste sistema.");
    arena = Arena.ofShared();
    try {
      library = SymbolLookup.libraryLookup(dll, arena);
      call(
          "win_sparkle_set_app_details",
          FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
          wide("ARPEON"),
          wide("ARPEON"),
          wide(version));
      call(
          "win_sparkle_set_appcast_url",
          FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
          arena.allocateFrom(settings.appcastUrl()));
      int accepted =
          (int)
              call(
                  "win_sparkle_set_eddsa_public_key",
                  FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                  arena.allocateFrom(settings.publicKey()));
      if (accepted == 0) throw new IOException("WinSparkle rejected the update signing key");
      call(
          "win_sparkle_set_registry_path",
          FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
          arena.allocateFrom("Software\\ARPEON\\Updates\\" + settings.channel()));
      automaticChecking = preferences.automatic();
      call(
          "win_sparkle_set_automatic_check_for_updates",
          FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
          automaticChecking ? 1 : 0);
      call(
          "win_sparkle_set_update_check_interval",
          FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
          86400);
      call(
          "win_sparkle_set_lang",
          FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
          arena.allocateFrom("pt-BR"));
      callback("win_sparkle_set_error_callback", "updateFailed");
      callback("win_sparkle_set_did_find_update_callback", "updateFound");
      callback("win_sparkle_set_did_not_find_update_callback", "updateNotFound");
      callback("win_sparkle_set_update_cancelled_callback", "updateCancelled");
      var lookup = MethodHandles.lookup();
      var allowed =
          Linker.nativeLinker()
              .upcallStub(
                  lookup
                      .findVirtual(
                          WindowsUpdater.class, "canShutdown", MethodType.methodType(int.class))
                      .bindTo(this),
                  FunctionDescriptor.of(ValueLayout.JAVA_INT),
                  arena);
      var requested =
          Linker.nativeLinker()
              .upcallStub(
                  lookup
                      .findVirtual(
                          WindowsUpdater.class,
                          "requestShutdown",
                          MethodType.methodType(void.class))
                      .bindTo(this),
                  FunctionDescriptor.ofVoid(),
                  arena);
      call(
          "win_sparkle_set_can_shutdown_callback",
          FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
          allowed);
      call(
          "win_sparkle_set_shutdown_request_callback",
          FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
          requested);
      call("win_sparkle_init", FunctionDescriptor.ofVoid());
    } catch (Throwable failure) {
      arena.close();
      arena = null;
      library = null;
      throw new IOException("Não foi possível preparar o atualizador seguro.", failure);
    }
  }

  synchronized Status status() {
    return new Status(
        settings.enabled() && library != null,
        settings.channel(),
        version,
        !settings.enabled()
            ? "Atualizações indisponíveis neste pacote. A publicação oficial ainda não foi"
                + " configurada."
            : library == null
                ? "Atualizador indisponível. Consulte os logs do aplicativo."
                : switch (state) {
                  case "checking" -> "Verificando atualizações…";
                  case "available" ->
                      "Uma atualização está disponível. Escolha quando instalar na janela de"
                          + " atualização.";
                  case "current" -> "Você está usando a versão mais recente deste canal.";
                  case "error" ->
                      "Não foi possível concluir a atualização. Verifique sua conexão e tente"
                          + " novamente.";
                  case "cancelled" ->
                      "Atualização adiada. Você pode verificar novamente quando quiser.";
                  case "installing" -> "Preparando a instalação da atualização…";
                  default -> "Verificação de atualizações disponível.";
                },
        automaticChecking,
        state,
        lastCheckedAt);
  }

  synchronized void check() throws IOException {
    if (!status().enabled()) throw new IOException(status().message());
    if (state.equals("checking")) return;
    try {
      state = "checking";
      call("win_sparkle_check_update_with_ui", FunctionDescriptor.ofVoid());
    } catch (Throwable failure) {
      updateFailed();
      throw new IOException("Não foi possível verificar atualizações.", failure);
    }
  }

  synchronized void automaticChecking(boolean enabled) throws IOException {
    if (!status().enabled()) throw new IOException(status().message());
    try {
      call(
          "win_sparkle_set_automatic_check_for_updates",
          FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
          enabled ? 1 : 0);
      try {
        preferences.save(enabled);
      } catch (IOException failure) {
        call(
            "win_sparkle_set_automatic_check_for_updates",
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
            automaticChecking ? 1 : 0);
        throw failure;
      }
      automaticChecking = enabled;
    } catch (Throwable failure) {
      throw new IOException("Não foi possível guardar a preferência de atualização.", failure);
    }
  }

  private void callback(String setter, String method) throws Throwable {
    var handle =
        MethodHandles.lookup()
            .findVirtual(WindowsUpdater.class, method, MethodType.methodType(void.class))
            .bindTo(this);
    call(
        setter,
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        Linker.nativeLinker().upcallStub(handle, FunctionDescriptor.ofVoid(), arena));
  }

  private void updateFailed() {
    state = "error";
    System.getLogger(WindowsUpdater.class.getName())
        .log(System.Logger.Level.WARNING, "WinSparkle reported an update failure");
  }

  private void updateFound() {
    lastCheckedAt = Instant.now().toString();
    state = "available";
  }

  private void updateNotFound() {
    lastCheckedAt = Instant.now().toString();
    state = "current";
  }

  private void updateCancelled() {
    state = "cancelled";
  }

  private int canShutdown() {
    try {
      return canShutdown.getAsBoolean() ? 1 : 0;
    } catch (RuntimeException failure) {
      System.getLogger(WindowsUpdater.class.getName())
          .log(System.Logger.Level.ERROR, "Update shutdown check failed", failure);
      return 0;
    }
  }

  private void requestShutdown() {
    try {
      state = "installing";
      shutdown.run();
    } catch (RuntimeException failure) {
      System.getLogger(WindowsUpdater.class.getName())
          .log(System.Logger.Level.ERROR, "Update shutdown request failed", failure);
    }
  }

  private MemorySegment wide(String value) {
    return arena.allocateFrom(ValueLayout.JAVA_CHAR, (value + "\0").toCharArray());
  }

  private Object call(String name, FunctionDescriptor descriptor, Object... arguments)
      throws Throwable {
    var symbol =
        library.find(name).orElseThrow(() -> new IOException("Missing WinSparkle symbol: " + name));
    return Linker.nativeLinker().downcallHandle(symbol, descriptor).invokeWithArguments(arguments);
  }

  @Override
  public synchronized void close() {
    if (arena == null) return;
    try {
      call("win_sparkle_cleanup", FunctionDescriptor.ofVoid());
    } catch (Throwable failure) {
      System.getLogger(WindowsUpdater.class.getName())
          .log(System.Logger.Level.WARNING, "Updater cleanup failed", failure);
    } finally {
      arena.close();
      arena = null;
      library = null;
    }
  }
}
