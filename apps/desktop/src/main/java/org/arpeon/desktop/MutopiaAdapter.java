package org.arpeon.desktop;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;
import org.arpeon.importer.DraftScoreBuilder;
import org.arpeon.importer.LocalImporters;

/** One hash-pinned public MIDI selection, with no HTML extraction or site crawling. */
final class MutopiaAdapter implements ScoreSourceProvider {
  static final int MAX_BYTES = 1_048_576;

  record Response(int status, byte[] bytes, String retryAfter) {}

  @FunctionalInterface
  interface Fetcher {
    Response get(URI uri) throws IOException;
  }

  private final Fetcher fetcher;
  private final Supplier<Instant> clock;
  private Instant nextRequest = Instant.MIN;
  private Instant cachedAt = Instant.MIN;
  private byte[] cached;

  MutopiaAdapter() {
    this(MutopiaAdapter::fetch, Instant::now);
  }

  MutopiaAdapter(Fetcher fetcher, Supplier<Instant> clock) {
    this.fetcher = fetcher;
    this.clock = clock;
  }

  @Override
  public SourceRegistry.Provider descriptor() {
    return SourceRegistry.byId("mutopia");
  }

  @Override
  public synchronized Result preview(String selection, String contact) throws IOException {
    var entry = MutopiaCatalogue.entry(selection);
    Instant now = clock.get();
    byte[] archive;
    if (cached != null && !now.isBefore(cachedAt) && now.isBefore(cachedAt.plusSeconds(600))) {
      archive = cached;
    } else {
      if (now.isBefore(nextRequest))
        throw new IllegalStateException(
            "A fonte Mutopia está em espera. Aguarde antes de tentar novamente.");
      nextRequest = now.plusSeconds(5);
      var response = fetcher.get(URI.create(MutopiaCatalogue.ARCHIVE));
      if (response.status() == 429 || response.status() == 503) {
        nextRequest = retryAt(response.retryAfter(), now);
        throw new IllegalStateException(
            "Mutopia solicitou uma pausa. Aguarde antes de tentar novamente.");
      }
      if (response.status() != 200)
        throw new IOException("A fonte Mutopia não respondeu com o arquivo esperado.");
      archive = response.bytes();
      if (archive == null
          || archive.length == 0
          || archive.length > MAX_BYTES
          || !ProjectProvenance.hash(archive).equals(MutopiaCatalogue.ARCHIVE_SHA256))
        throw new IOException(
            "O pacote Mutopia mudou ou falhou na integridade. A revisão precisa ser atualizada.");
      cached = archive.clone();
      cachedAt = now;
    }
    byte[] bytes = member(archive, entry);
    var draft = LocalImporters.midi(bytes, entry.filename());
    DraftScoreBuilder.build(draft, 4, 480);
    return new Result(
        draft,
        new ImportWorkspace.Source(
            "mutopia",
            entry.pageUrl(),
            now.toString(),
            "DECLARED_REVIEW_REQUIRED",
            entry.license(),
            entry.licenseUrl(),
            List.of(
                entry.credit(),
                "Fonte LilyPond revisada: " + entry.sourceUrl(),
                "Pacote MIDI SHA-256: " + MutopiaCatalogue.ARCHIVE_SHA256,
                "Membro selecionado: " + entry.filename(),
                "MIDI monofônico convertido em rascunho 4/4; revise grafia, ritmo, andamento e"
                    + " repetições.",
                "Original MIDI selecionado preservado; o pacote ZIP não é gravado no projeto."),
            new ImportWorkspace.SourceMetadata(entry.title(), entry.composer(), "flute")),
        new OriginalScore(entry.filename(), bytes));
  }

  private static byte[] member(byte[] archive, MutopiaCatalogue.Entry selected) throws IOException {
    byte[] result = null;
    int count = 0, expanded = 0;
    var names = new java.util.HashSet<String>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (++count > 8
            || entry.isDirectory()
            || !entry.getName().matches("bwv1013(?:-[1-3])?\\.mid")
            || !names.add(entry.getName()))
          throw new IOException("Conteúdo inesperado no pacote Mutopia.");
        byte[] bytes = zip.readNBytes(MAX_BYTES + 1);
        expanded += bytes.length;
        if (bytes.length > MAX_BYTES || expanded > MAX_BYTES)
          throw new IOException("Pacote Mutopia excede o limite.");
        if (entry.getName().equals(selected.filename())) result = bytes;
      }
    }
    if (count != 4
        || result == null
        || result.length != selected.byteSize()
        || !ProjectProvenance.hash(result).equals(selected.sha256()))
      throw new IOException("O MIDI selecionado falhou na integridade.");
    return result;
  }

  private static Instant retryAt(String value, Instant now) {
    try {
      long seconds = Math.max(5, Long.parseLong(value));
      try {
        return now.plusSeconds(seconds);
      } catch (java.time.DateTimeException overflow) {
        return Instant.MAX;
      }
    } catch (NumberFormatException invalid) {
      try {
        var date =
            java.time.ZonedDateTime.parse(
                    value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant();
        return date.isAfter(now.plusSeconds(5)) ? date : now.plusSeconds(5);
      } catch (RuntimeException missing) {
        return now.plusSeconds(60);
      }
    }
  }

  private static Response fetch(URI uri) throws IOException {
    try (var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
      var request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(10))
              .header("User-Agent", "ARPEON/0.1.2 (+https://github.com/junior089/ARPEON)")
              .header("Accept", "application/zip")
              .GET()
              .build();
      try {
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
          if (response.statusCode() == 429 || response.statusCode() == 503)
            return new Response(
                response.statusCode(),
                new byte[0],
                response.headers().firstValue("Retry-After").orElse(""));
          if (response.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_BYTES)
            throw new IOException("O pacote Mutopia excede 1 MiB.");
          var reader =
              new java.util.concurrent.FutureTask<byte[]>(() -> body.readNBytes(MAX_BYTES + 1));
          Thread.ofVirtual().start(reader);
          try {
            return new Response(
                response.statusCode(), reader.get(10, java.util.concurrent.TimeUnit.SECONDS), "");
          } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IOException("Tempo limite ao receber o pacote Mutopia.", timeout);
          } catch (java.util.concurrent.ExecutionException failed) {
            throw new IOException("Falha ao receber o pacote Mutopia.", failed.getCause());
          } finally {
            reader.cancel(true);
          }
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("Consulta Mutopia interrompida.", interrupted);
      }
    }
  }
}
