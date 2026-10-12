package org.arpeon.desktop;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;

record UpdateSettings(boolean enabled, String channel, String appcastUrl, String publicKey) {
  static UpdateSettings load(Path file) throws IOException {
    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 4096)
      throw new IOException("Update configuration is missing or unsafe");
    var properties = new Properties();
    try (var reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    if (!properties
        .stringPropertyNames()
        .equals(Set.of("enabled", "channel", "appcastUrl", "publicKey")))
      throw new IOException("Unexpected update configuration fields");
    String enabled = properties.getProperty("enabled");
    String channel = properties.getProperty("channel");
    String url = properties.getProperty("appcastUrl");
    String key = properties.getProperty("publicKey");
    if (!Set.of("true", "false").contains(enabled)
        || !Set.of("stable", "beta", "nightly", "testing").contains(channel))
      throw new IOException("Invalid update channel or activation flag");
    if (enabled.equals("true")) {
      try {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || uri.getFragment() != null
            || Base64.getDecoder().decode(key).length != 32)
          throw new IllegalArgumentException(
              "HTTPS feed and a 32-byte Ed25519 public key are required");
      } catch (IllegalArgumentException failure) {
        throw new IOException("Invalid signed update configuration", failure);
      }
    } else if (!url.isEmpty() || !key.isEmpty()) {
      throw new IOException("An inactive build must not carry a live update endpoint or key");
    }
    return new UpdateSettings(enabled.equals("true"), channel, url, key);
  }
}
