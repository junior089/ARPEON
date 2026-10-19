package org.arpeon.desktop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.arpeon.musicxml.MusicXmlImporter;

/** Immutable input bytes, separate from every normalized or edited score revision. */
final class OriginalScore {
  private final byte[] bytes;
  private final String filename;
  private final String sha256;
  private final String format;

  OriginalScore(String filename, byte[] bytes) throws IOException {
    if (filename == null
        || filename.length() > 200
        || !filename.matches("[^/\\\\:]+\\.(?i:musicxml|xml|mxl|mscz)")
        || filename
            .codePoints()
            .anyMatch(
                code -> Character.isISOControl(code) || Character.getType(code) == Character.FORMAT)
        || bytes == null
        || bytes.length == 0
        || bytes.length > MusicXmlImporter.MAX_BYTES)
      throw new IOException("Original de partitura inválido ou acima de 128 MiB.");
    this.filename = filename;
    this.bytes = bytes.clone();
    sha256 = ProjectProvenance.hash(this.bytes);
    String extension = filename.substring(filename.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
    format = extension.equals("XML") ? "MUSICXML" : extension;
  }

  String filename() {
    return filename;
  }

  String sha256() {
    return sha256;
  }

  String format() {
    return format;
  }

  int size() {
    return bytes.length;
  }

  byte[] copyBytes() {
    return bytes.clone();
  }

  void write(Path path, ProjectStore.DurableWriter writer) throws IOException {
    writer.write(path, copyBytes());
  }
}
