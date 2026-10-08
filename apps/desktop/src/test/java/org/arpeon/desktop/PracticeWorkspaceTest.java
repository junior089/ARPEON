package org.arpeon.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import org.arpeon.musicxml.MusicXmlImporter;
import org.arpeon.tuner.TunerObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PracticeWorkspaceTest {
  @TempDir Path temporary;

  @Test
  void generatesExercisesInTheScoreDomainAndKeepsFluteRange() throws IOException {
    try (var practice = new PracticeWorkspace()) {
      var note = practice.create("NOTE", "C_MAJOR", 4, "MAJOR", 80, false, 0);
      assertEquals("C4", note.targets().getFirst().pitch());
      assertTrue(note.xml().contains("<score-partwise"));
      assertEquals("flute-cc0-chart-v1", note.fingeringDatasetId());
      var imported =
          new MusicXmlImporter()
              .read(
                  new ByteArrayInputStream(note.xml().getBytes(StandardCharsets.UTF_8)),
                  "exercise.musicxml");
      assertEquals("flute", imported.instruments().get("practice"));
      assertEquals(1, note.targets().size());

      var scale = practice.create("SCALE", "C_MAJOR", 4, "MAJOR", 80, false, 0);
      assertEquals(15, scale.targets().size());
      assertEquals("C5", scale.targets().get(7).pitch());
      assertEquals("C4", scale.targets().getLast().pitch());

      var minor = practice.create("SCALE", "A_MINOR", 4, "MELODIC_MINOR", 80, false, 0);
      assertEquals("F#5", minor.targets().get(5).pitch());
      assertEquals("F5", minor.targets().get(9).pitch());

      var arpeggio = practice.create("ARPEGGIO", "A_MINOR", 4, "NATURAL_MINOR", 80, false, 0);
      assertEquals("C5", arpeggio.targets().get(1).pitch());

      var rhythm = practice.create("RHYTHM", "C_MAJOR", 4, "MAJOR", 80, false, 0);
      assertEquals(240, rhythm.targets().get(2).durationTicks());
      assertEquals(1920, rhythm.transport().totalTicks());

      assertThrows(
          IllegalArgumentException.class,
          () -> practice.create("SCALE", "Cb_MAJOR", 4, "MAJOR", 80, false, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> practice.create("SCALE", "A_MINOR", 4, "MAJOR", 80, false, 0));
    }
  }

  @Test
  void evaluatesPitchAndLongToneWithMeasurements() {
    try (var practice = new PracticeWorkspace()) {
      practice.create("NOTE", "C_MAJOR", 4, "MAJOR", 80, false, 0);
      practice.start();
      var wrong = practice.observe(tracking(Instant.now(), 293.66));
      assertEquals("INCORRECT", wrong.feedback().state());
      var correct = practice.observe(tracking(Instant.now().plusMillis(200), 261.63));
      assertEquals("COMPLETE", correct.feedback().state());
      assertFalse(correct.active());
      assertEquals(1, practice.finish().reached());
      practice.markSaved();

      practice.create("LONG_TONE", "C_MAJOR", 4, "MAJOR", 80, false, 0);
      practice.start();
      assertEquals(3000, practice.snapshot().longToneTargetMs());
      Instant base = Instant.now();
      for (int i = 0; i <= 20; i++) practice.observe(tracking(base.plusMillis(i * 200L), 261.63));
      var result = practice.snapshot();
      assertEquals("COMPLETE", result.feedback().state());
      assertEquals(3000.0, result.feedback().heldMs());
      assertTrue(result.feedback().stabilityCents() < 1);
      assertTrue(practice.finish().completed());
      practice.markSaved();
    }
  }

  @Test
  void rhythmTapReportsTimingAndHistoryPersistsWithoutAudio() throws IOException {
    try (var practice = new PracticeWorkspace()) {
      practice.create("RHYTHM", "C_MAJOR", 4, "MAJOR", 80, false, 0);
      practice.start();
      var tapped = practice.tap();
      assertEquals(1, tapped.targetIndex());
      assertTrue(tapped.feedback().timingErrorMs() != null);
      var summary = practice.finish();
      assertFalse(summary.completed());
      var history = new PracticeHistory(temporary.resolve("history.json"));
      history.add(summary);
      assertTrue(practice.snapshot().attemptOpen());
      assertEquals(summary, history.list().getFirst());
      assertFalse(
          java.nio.file.Files.readString(temporary.resolve("history.json")).contains("261.63"));
      practice.markSaved();
      assertFalse(practice.snapshot().attemptOpen());
    }
  }

  @Test
  void longToneTracksAudibleBreaksWithoutCountingSilenceAsHeldTime() {
    try (var practice = new PracticeWorkspace()) {
      practice.create("LONG_TONE", "C_MAJOR", 4, "MAJOR", 80, false, 0);
      practice.start();
      Instant base = Instant.now();
      practice.observe(tracking(base, 261.63));
      practice.observe(tracking(base.plusMillis(200), 261.63));
      var silence =
          new TunerObservation(
              TunerObservation.State.SILENCE,
              null,
              null,
              null,
              null,
              0,
              base.plusMillis(400),
              20,
              3,
              10,
              0,
              0,
              48000,
              440,
              "SILENCE");
      var result = practice.observe(silence);
      assertEquals(200.0, result.feedback().heldMs());
      assertEquals(1, result.feedback().interruptions());
      result = practice.observe(tracking(base.plusMillis(600), 261.63));
      assertEquals(200.0, result.feedback().heldMs());
    }
  }

  private static TunerObservation tracking(Instant time, double frequency) {
    return new TunerObservation(
        TunerObservation.State.TRACKING,
        frequency,
        "C4",
        4,
        0.0,
        0.95,
        time,
        20,
        3,
        10,
        0,
        0,
        48000,
        440,
        "TRACKING");
  }
}
