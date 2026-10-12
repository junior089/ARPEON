package org.arpeon.omr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class OmrPdfPreviewTest {
  private static OmrInput document() throws Exception {
    try (var pdf = new PDDocument()) {
      for (int index = 0; index < 2; index++) {
        var page = new PDPage(new PDRectangle(2000, 1500));
        if (index == 1) page.setRotation(90);
        pdf.addPage(page);
        try (var content = new PDPageContentStream(pdf, page)) {
          content.setNonStrokingColor(index == 0 ? java.awt.Color.RED : java.awt.Color.BLUE);
          content.addRect(0, 0, 2000, 1500);
          content.fill();
        }
      }
      var bytes = new ByteArrayOutputStream();
      pdf.save(bytes);
      return new OmrInput(bytes.toByteArray(), "Canção – flauta.pdf");
    }
  }

  @Test
  void rendersDistinctPagesAsBoundedPngAndRetainsRotation() throws Exception {
    var input = document();
    var first = ImageIO.read(new ByteArrayInputStream(OmrPdfPreview.render(input, 1)));
    var second = ImageIO.read(new ByteArrayInputStream(OmrPdfPreview.render(input, 2)));
    assertTrue(first.getWidth() <= 1600 && first.getHeight() <= 1600);
    assertTrue(second.getWidth() <= 1600 && second.getHeight() <= 1600);
    assertTrue(first.getWidth() > first.getHeight());
    assertTrue(second.getWidth() < second.getHeight());
    assertEquals(java.awt.Color.RED.getRGB(), first.getRGB(20, 20));
    assertEquals(java.awt.Color.BLUE.getRGB(), second.getRGB(20, 20));
  }

  @Test
  void rejectsOutOfBoundsPagesAndInvalidOrNonPdfInput() throws Exception {
    var input = document();
    assertThrows(IOException.class, () -> OmrPdfPreview.render(input, 0));
    assertThrows(IOException.class, () -> OmrPdfPreview.render(input, 3));
    assertThrows(
        IOException.class, () -> OmrPdfPreview.render(new OmrInput(input.bytes(), "file.png"), 1));
    assertThrows(
        IOException.class, () -> OmrPdfPreview.render(new OmrInput(new byte[16], "fake.pdf"), 1));
  }
}
