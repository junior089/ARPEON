package org.arpeon.omr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Renders one bounded original page locally, without requiring a browser PDF plug-in. */
public final class OmrPdfPreview {
  private OmrPdfPreview() {}

  public static byte[] render(OmrInput input, int pageNumber) throws IOException {
    if (!input.filename().toLowerCase(Locale.ROOT).endsWith(".pdf"))
      throw new IOException("Escolha um PDF para a comparação por páginas.");
    int pages = OmrInputValidator.validate(input);
    if (pageNumber < 1 || pageNumber > pages)
      throw new IOException("Página fora do PDF selecionado.");
    try (var pdf = Loader.loadPDF(input.bytes())) {
      var box = pdf.getPage(pageNumber - 1).getCropBox();
      float scale = Math.min(1.5f, 1600f / Math.max(box.getWidth(), box.getHeight()));
      var renderer = new PDFRenderer(pdf);
      renderer.setSubsamplingAllowed(true);
      var image = renderer.renderImage(pageNumber - 1, scale, ImageType.RGB);
      try {
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes))
          throw new IOException("Não foi possível preparar a imagem desta página.");
        return bytes.toByteArray();
      } finally {
        image.flush();
      }
    } catch (RuntimeException failure) {
      throw new IOException(
          "Não foi possível exibir esta página. Abra o arquivo original.", failure);
    }
  }
}
