package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import org.arpeon.importer.LocalImporters;
import org.arpeon.musicxml.MusicXmlImporter;
import org.junit.jupiter.api.Test;

/** Offline fixture benchmark; never interprets mock-fetch numbers as network latency. */
class SourceImportBenchmarkTest {
  @Test
  void reportsSeparateFilterMockFetchAndParserCost() throws Exception {
    URI approved = URI.create("https://commons.wikimedia.org/wiki/File:Example.mid");
    byte[] midi = midi();
    byte[] metadata = "{\"query\":{\"pages\":[]}}".getBytes(StandardCharsets.UTF_8);
    var fetcher =
        (WikimediaCommonsAdapter.Fetcher)
            (uri, agent, max) ->
                new WikimediaCommonsAdapter.Response(
                    200,
                    uri.getHost().equals("commons.wikimedia.org")
                        ? "application/json"
                        : "audio/midi",
                    Arrays.copyOf(
                        uri.getHost().equals("commons.wikimedia.org") ? metadata : midi,
                        uri.getHost().equals("commons.wikimedia.org")
                            ? metadata.length
                            : midi.length),
                    "");
    bench("filter", 5000, () -> SourceRegistry.eligible(approved));
    bench(
        "mock-fetch",
        1000,
        () -> {
          try {
            fetcher.get(
                URI.create("https://commons.wikimedia.org/w/api.php"), "ARPEON test", 131072);
            fetcher.get(
                URI.create("https://upload.wikimedia.org/wikipedia/commons/a/example.mid"),
                "ARPEON test",
                1048576);
          } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
          }
        });
    bench(
        "midi-parse",
        200,
        () -> {
          try {
            assertTrue(LocalImporters.midi(midi, "fixture.mid").notes().size() == 1);
          } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
          }
        });
    System.out.println(
        "SOURCE_BENCH fixture metadataBytes="
            + metadata.length
            + " midiBytes="
            + midi.length
            + " network=simulated");
  }

  @Test
  void reportsStreamingMusicXmlCostOnSyntheticNearLimitScore() throws Exception {
    byte[] xml = syntheticMusicXml(2_048, 8).getBytes(StandardCharsets.UTF_8);
    var importer = new MusicXmlImporter();
    bench(
        "musicxml-stream-near-limit",
        1,
        3,
        () -> {
          try {
            var imported = importer.read(new java.io.ByteArrayInputStream(xml), "synthetic.xml");
            assertTrue(imported.score().parts().getFirst().measures().size() == 2_048);
            assertTrue(
                imported.score().parts().getFirst().measures().stream()
                        .mapToInt(measure -> measure.events().size())
                        .sum()
                    == 16_384);
          } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
          }
        });
    System.out.println(
        "SOURCE_BENCH fixture=generated-synthetic measures=2048 events=16384 bytes="
            + xml.length
            + " parser=stax-stream network=none");
  }

  private static void bench(String stage, int count, Runnable operation) {
    bench(stage, 100, count, operation);
  }

  private static void bench(String stage, int warmup, int count, Runnable operation) {
    for (int i = 0; i < warmup; i++) operation.run();
    var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long thread = Thread.currentThread().threadId();
    long allocatedBefore =
        bean.isThreadAllocatedMemorySupported() ? bean.getThreadAllocatedBytes(thread) : -1;
    long before = System.nanoTime();
    for (int i = 0; i < count; i++) operation.run();
    long elapsed = System.nanoTime() - before;
    long allocated =
        allocatedBefore < 0 ? -1 : bean.getThreadAllocatedBytes(thread) - allocatedBefore;
    System.out.println(
        "SOURCE_BENCH stage="
            + stage
            + " count="
            + count
            + " elapsedMs="
            + elapsed / 1_000_000.0
            + " allocatedBytes="
            + allocated);
  }

  private static String syntheticMusicXml(int measures, int eventsPerMeasure) {
    var xml =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><score-partwise version=\"4.0\">"
                + "<part-list><score-part id=\"P1\"><part-name>Synthetic</part-name></score-part>"
                + "</part-list><part id=\"P1\">");
    for (int measure = 0; measure < measures; measure++) {
      xml.append("<measure number=\"").append(measure + 1).append("\">");
      if (measure == 0)
        xml.append(
            "<attributes><divisions>8</divisions><time><beats>4</beats><beat-type>4</beat-type>"
                + "</time></attributes>");
      for (int event = 0; event < eventsPerMeasure; event++)
        xml.append(
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration>"
                + "<type>eighth</type></note>");
      xml.append("</measure>");
    }
    return xml.append("</part></score-partwise>").toString();
  }

  private static byte[] midi() throws Exception {
    var sequence = new Sequence(Sequence.PPQ, 480);
    var track = sequence.createTrack();
    var on = new ShortMessage();
    on.setMessage(ShortMessage.NOTE_ON, 0, 60, 100);
    var off = new ShortMessage();
    off.setMessage(ShortMessage.NOTE_OFF, 0, 60, 0);
    track.add(new MidiEvent(on, 0));
    track.add(new MidiEvent(off, 480));
    var out = new ByteArrayOutputStream();
    MidiSystem.write(sequence, 0, out);
    return out.toByteArray();
  }
}
