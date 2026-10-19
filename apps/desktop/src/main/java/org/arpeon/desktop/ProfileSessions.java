package org.arpeon.desktop;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.arpeon.tuner.TunerService;

final class ProfileSessions implements AutoCloseable {
  private final LocalAccountStore account;
  private final Path deviceData;
  private final Supplier<TunerService> tuners;
  private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor();
  private final Set<DesktopServer> retiring = new HashSet<>();
  private DesktopServer active;
  private Runnable shutdown = () -> {};
  private WindowsUpdater updater;
  private boolean closed;

  ProfileSessions(Path root, int maxProfiles, Supplier<TunerService> tuners) throws IOException {
    this.deviceData = root.toAbsolutePath().normalize();
    this.account = new LocalAccountStore(root, maxProfiles);
    this.tuners = tuners;
    var state = account.snapshot();
    active = create(state.activeProfileId(), !state.openLastProfile());
  }

  private DesktopServer create(String id, boolean selectionRequired) throws IOException {
    Path directory = account.profileDirectory(id);
    LocalAccountStore.requireDirectory(directory);
    var server = DesktopServer.forProfile(tuners.get(), directory, deviceData);
    server.profileSession(this, id, selectionRequired);
    server.onShutdown(() -> shutdown.run());
    if (updater != null) server.attachUpdater(updater);
    return server;
  }

  synchronized void start() {
    active.start();
  }

  synchronized URI launchUri() {
    return active.launchUri();
  }

  synchronized boolean canUpdate() {
    return !closed && active.canUpdate();
  }

  synchronized void onShutdown(Runnable action) {
    shutdown = action;
  }

  synchronized void attachUpdater(WindowsUpdater value) {
    updater = value;
    active.attachUpdater(value);
  }

  LocalAccountStore account() {
    return account;
  }

  synchronized URI select(DesktopServer previous, String id) throws IOException {
    if (closed || previous != active)
      throw new IllegalStateException("A sessão deste perfil já foi encerrada.");
    if (account.profile(id).archived())
      throw new IllegalStateException("Restaure o perfil antes de selecioná-lo.");
    if (!previous.canUpdate())
      throw new IllegalStateException(
          "Salve o projeto e encerre a captura, a tentativa ou o reconhecimento antes de trocar de"
              + " perfil.");
    var next = create(id, false);
    try {
      next.start();
      account.select(id);
    } catch (IOException | RuntimeException failure) {
      next.close();
      throw failure;
    }
    previous.retireProfile();
    active = next;
    retiring.add(previous);
    cleanup.schedule(
        () -> {
          previous.close();
          synchronized (ProfileSessions.this) {
            retiring.remove(previous);
          }
        },
        1,
        TimeUnit.SECONDS);
    return next.launchUri();
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    cleanup.shutdownNow();
    active.close();
    for (var server : retiring) server.close();
    retiring.clear();
  }
}
