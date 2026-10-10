package org.arpeon.omr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AudiverisOmrEngineTest {
  @Test
  void consumesOneBoundedXmlExportAndRemovesTemporaryJob() throws Exception {
    byte[] image = image();
    var job = new AtomicReference<Path>();
    var engine =
        new AudiverisOmrEngine(
            javaExecutable(),
            (executable, source, output) -> {
              assertEquals(javaExecutable(), executable);
              assertTrue(Files.isRegularFile(source));
              job.set(source.getParent());
              Path folder = Files.createDirectory(output.resolve("source"));
              Files.writeString(
                  folder.resolve("source.xml"), OmrMusicXmlTest.SIMPLE, StandardCharsets.UTF_8);
            });
    var result = engine.recognize(new OmrInput(image, "page.png"));
    assertEquals("page.png", result.sourceFilename());
    assertEquals(64, result.sourceSha256().length());
    assertEquals(1, result.pages());
    assertEquals(
        4, result.imported().score().parts().getFirst().measures().getFirst().events().size());
    assertEquals(1, result.pagesRequiringReview().size());
    assertFalse(Files.exists(job.get()));
  }

  @Test
  void rejectsAmbiguousMultipleMovementOutput() throws Exception {
    var engine =
        new AudiverisOmrEngine(
            javaExecutable(),
            (executable, source, output) -> {
              Files.writeString(output.resolve("one.xml"), OmrMusicXmlTest.SIMPLE);
              Files.writeString(output.resolve("two.xml"), OmrMusicXmlTest.SIMPLE);
            });
    assertThrows(IOException.class, () -> engine.recognize(new OmrInput(image(), "page.png")));
  }

  private static Path javaExecutable() {
    String name =
        System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
            ? "java.exe"
            : "java";
    Path executable = Path.of(System.getProperty("java.home"), "bin", name);
    assertTrue(Files.isRegularFile(executable), executable.toString());
    return executable;
  }

  private static byte[] image() throws IOException {
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(120, 120, BufferedImage.TYPE_BYTE_GRAY), "png", output);
    return output.toByteArray();
  }
}
