package org.arpeon.omr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.apache.pdfbox.Loader;

/** Rejects oversized or deceptive files before the OMR process sees them. */
public final class OmrInputValidator {
  public static final int MAX_PDF_BYTES = 64 * 1024 * 1024;
  public static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
  public static final int MAX_PAGES = 32;

  private OmrInputValidator() {}

  public static int validate(OmrInput input) throws IOException {
    String name = input.filename();
    if (name.length() > 120 || !name.matches("[A-Za-z0-9._ -]+\\.(?i:pdf|png|jpg|jpeg)"))
      throw new IOException("Use a short PDF, PNG or JPEG filename without a path");
    byte[] bytes = input.bytes();
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".pdf")) {
      if (bytes.length < 8 || bytes.length > MAX_PDF_BYTES || !starts(bytes, "%PDF-"))
        throw new IOException("Invalid PDF or PDF exceeds 64 MiB");
      try (var pdf = Loader.loadPDF(bytes)) {
        if (pdf.isEncrypted()) throw new IOException("Encrypted PDFs are unsupported");
        int count = pdf.getNumberOfPages();
        if (count < 1 || count > MAX_PAGES)
          throw new IOException("PDF must contain one to 32 pages");
        for (var page : pdf.getPages()) {
          var box = page.getCropBox();
          if (box == null
              || box.getWidth() < 30
              || box.getHeight() < 30
              || box.getWidth() > 2500
              || box.getHeight() > 2500)
            throw new IOException("PDF page dimensions are unsupported");
        }
        return count;
      } catch (RuntimeException failure) {
        throw new IOException("Invalid PDF structure", failure);
      }
    }
    if (bytes.length < 16 || bytes.length > MAX_IMAGE_BYTES)
      throw new IOException("Image exceeds 32 MiB or is empty");
    String format = lower.endsWith(".png") ? "png" : "JPEG";
    if (format.equals("png") ? !png(bytes) : !jpeg(bytes))
      throw new IOException("Image signature does not match filename");
    try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
      if (!readers.hasNext()) throw new IOException("Image decoder is unavailable");
      ImageReader reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width < 100
            || height < 100
            || width > 6000
            || height > 6000
            || (long) width * height > 64_000_000)
          throw new IOException("Image dimensions are unsupported");
      } finally {
        reader.dispose();
      }
    } catch (RuntimeException failure) {
      throw new IOException("Invalid image structure", failure);
    }
    return 1;
  }

  private static boolean starts(byte[] bytes, String signature) {
    for (int i = 0; i < signature.length(); i++)
      if (bytes[i] != (byte) signature.charAt(i)) return false;
    return true;
  }

  private static boolean png(byte[] bytes) {
    byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
    return true;
  }

  private static boolean jpeg(byte[] bytes) {
    return bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
  }
}
