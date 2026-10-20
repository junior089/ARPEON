package org.arpeon.desktop;

import java.net.URI;
import java.util.List;

/** Reviewed access modes. An unknown host has no automated import permission. */
final class SourceRegistry {
  record Capabilities(
      boolean search,
      boolean importByUrl,
      List<String> formats,
      boolean selectParts,
      boolean connectAccount) {
    Capabilities {
      formats = List.copyOf(formats);
    }
  }

  record Provider(
      String id,
      String host,
      String accessMode,
      boolean authenticationRequired,
      boolean automationAllowed,
      String rateLimit,
      String contentLicenseNotes,
      String lastTermsReview,
      String implementationStatus,
      String name,
      Capabilities capabilities,
      String availabilityNote) {}

  private static final List<Provider> PROVIDERS =
      List.of(
          new Provider(
              "wikimedia-commons",
              "commons.wikimedia.org",
              "OFFICIAL_API",
              false,
              true,
              "One user-selected file; at most one import attempt per 2 seconds per app instance;"
                  + " provider throttling respected",
              "Per-file rights vary; show license metadata and require review of the file page",
              "2026-10-19",
              "MIDI_FILE_PAGE",
              "Wikimedia Commons",
              new Capabilities(false, true, List.of("MIDI"), false, false),
              "Importa um MIDI de uma página File: escolhida, com revisão. Sem busca ou seleção de"
                  + " partes."),
          new Provider(
              "musescore",
              "musescore.com",
              "USER_PROVIDED_EXPORT",
              true,
              false,
              "No automated requests",
              "User must lawfully export and supply their own file",
              "2026-10-08",
              "MANUAL_ONLY",
              "MuseScore.com",
              new Capabilities(false, false, List.of(), false, false),
              "Importe um arquivo exportado legitimamente pelo usuário. Sem acesso automatizado ou"
                  + " conexão de conta no ARPEON."),
          new Provider(
              "cifra-melodica",
              "ciframelodica.com.br",
              "USER_PASTE",
              false,
              false,
              "No automated requests",
              "User may paste legitimately obtained melodic notes",
              "2026-10-08",
              "MANUAL_ONLY",
              "Cifra Melódica",
              new Capabilities(false, false, List.of(), false, false),
              "Cole notas obtidas legitimamente. Sem busca ou download automatizado no ARPEON."));

  private SourceRegistry() {}

  static List<Provider> all() {
    return PROVIDERS;
  }

  static Provider byId(String id) {
    return PROVIDERS.stream()
        .filter(provider -> provider.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Fonte não registrada."));
  }

  static Provider requireUrlImport(URI uri, String format) {
    var provider = eligible(uri);
    if (!provider.capabilities().importByUrl()
        || !provider.capabilities().formats().contains(format))
      throw new IllegalArgumentException(
          "Esta fonte não implementa importação por URL neste formato.");
    return provider;
  }

  static Provider eligible(URI uri) {
    if (uri == null
        || !"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getPort() != -1
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null)
      throw new IllegalArgumentException(
          "Use a plain HTTPS file-page URL without query or fragment");
    for (var provider : PROVIDERS)
      if (provider.host().equalsIgnoreCase(uri.getHost())) {
        if (!provider.automationAllowed())
          throw new IllegalArgumentException(
              provider.id() + " allows only manual export or paste in ARPEON");
        return provider;
      }
    throw new IllegalArgumentException("This source has no reviewed automated access policy");
  }
}
