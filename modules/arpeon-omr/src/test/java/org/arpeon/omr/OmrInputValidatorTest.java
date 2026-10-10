package org.arpeon.omr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class OmrInputValidatorTest {
  @Test
  void boundsPdfPagesAndRejectsDeceptiveFiles() throws Exception {
    try (var pdf = new PDDocument()) {
      pdf.addPage(new PDPage());
      var output = new ByteArrayOutputStream();
      pdf.save(output);
      assertEquals(1, OmrInputValidator.validate(new OmrInput(output.toByteArray(), "sheet.pdf")));
      for (int i = 1; i < OmrInputValidator.MAX_PAGES; i++) pdf.addPage(new PDPage());
      output.reset();
      pdf.save(output);
      assertEquals(
          OmrInputValidator.MAX_PAGES,
          OmrInputValidator.validate(new OmrInput(output.toByteArray(), "max.pdf")));
      pdf.addPage(new PDPage());
      output.reset();
      pdf.save(output);
      assertThrows(
          IOException.class,
          () -> OmrInputValidator.validate(new OmrInput(output.toByteArray(), "many.pdf")));
    }
    assertThrows(
        IOException.class,
        () ->
            OmrInputValidator.validate(
                new OmrInput(new byte[] {'%', 'P', 'D', 'F', '-'}, "../escape.pdf")));
    assertThrows(
        IOException.class,
        () -> OmrInputValidator.validate(new OmrInput(new byte[100], "fake.pdf")));
  }

  @Test
  void validatesImageSignatureAndDimensionsBeforeRecognition() throws Exception {
    var image = new BufferedImage(120, 120, BufferedImage.TYPE_BYTE_GRAY);
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    assertEquals(1, OmrInputValidator.validate(new OmrInput(output.toByteArray(), "page.png")));
    assertThrows(
        IOException.class,
        () -> OmrInputValidator.validate(new OmrInput(output.toByteArray(), "page.jpg")));
    assertThrows(
        IOException.class,
        () ->
            OmrInputValidator.validate(
                new OmrInput(new byte[OmrInputValidator.MAX_IMAGE_BYTES + 1], "huge.png")));
  }
}
