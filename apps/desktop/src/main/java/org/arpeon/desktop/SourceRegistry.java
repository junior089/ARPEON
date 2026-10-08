package org.arpeon.desktop;

import java.net.URI;
import java.util.List;

/** Reviewed access modes. An unknown host has no automated import permission. */
final class SourceRegistry {
  record Provider(
      String id,
      String host,
      String accessMode,
      boolean authenticationRequired,
      boolean automationAllowed,
      String rateLimit,
      String contentLicenseNotes,
      String lastTermsReview,
      String implementationStatus) {}

  private static final List<Provider> PROVIDERS =
      List.of(
          new Provider(
              "wikimedia-commons",
              "commons.wikimedia.org",
              "OFFICIAL_API",
              false,
              true,
              "One user-selected file; at most one import attempt per 2 seconds per app instance; provider throttling respected",
              "Per-file rights vary; show license metadata and require review of the file page",
              "2026-10-08",
              "MIDI_FILE_PAGE"),
          new Provider(
              "musescore",
              "musescore.com",
              "USER_PROVIDED_EXPORT",
              true,
              false,
              "No automated requests",
              "User must lawfully export and supply their own file",
              "2026-10-08",
              "MANUAL_ONLY"),
          new Provider(
              "cifra-melodica",
              "ciframelodica.com.br",
              "USER_PASTE",
              false,
              false,
              "No automated requests",
              "User may paste legitimately obtained melodic notes",
              "2026-10-08",
              "MANUAL_ONLY"));

  private SourceRegistry() {}

  static List<Provider> all() {
    return PROVIDERS;
  }

  static Provider eligible(URI uri) {
    if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null
        || uri.getRawFragment() != null)
      throw new IllegalArgumentException("Use a plain HTTPS file-page URL without query or fragment");
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
