package org.arpeon.importer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import org.junit.jupiter.api.Test;

class LocalImportersTest {
  @Test
  void pastedMelodyKeepsMissingOctaveAndRhythmUntilExplicitChoice() throws Exception {
    var draft =
        LocalImporters.text(
            "do re#4 MI - fa".getBytes(StandardCharsets.UTF_8),
            "texto-colado.txt",
            "CIFRA_MELODICA");
    assertEquals("NONE", draft.rhythm());
    assertNull(draft.notes().get(0).octave());
    assertNull(draft.notes().get(0).durationTicks());
    assertEquals(4, draft.notes().size());
    var score = DraftScoreBuilder.build(draft, 4, 480);
    assertEquals(1, score.parts().getFirst().measures().size());
    assertEquals(4, score.parts().getFirst().measures().getFirst().events().size());
    assertEquals(
        draft.sha256(),
        LocalImporters.text(
                "do re#4 MI - fa".getBytes(StandardCharsets.UTF_8),
                "texto-colado.txt",
                "CIFRA_MELODICA")
            .sha256());
  }

  @Test
  void rejectsAmbiguousAndHostileTextWithoutGuessing() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LocalImporters.text(
                "DO | RE".getBytes(StandardCharsets.UTF_8), "paste.txt", "SOLFEGE"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LocalImporters.text(
                "DO x2 RE".getBytes(StandardCharsets.UTF_8), "paste.txt", "CIFRA_MELODICA"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LocalImporters.text("C D E".getBytes(StandardCharsets.UTF_8), "paste.txt", "SOLFEGE"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LocalImporters.text(new byte[LocalImporters.MAX_TEXT_BYTES + 1], "paste.txt", "NOTES"));
    assertThrows(
        java.io.IOException.class,
        () -> LocalImporters.text(new byte[] {(byte) 0xff}, "paste.txt", "NOTES"));
  }

  @Test
  void acceptsLocalDraftsBeyondTheFormerPrototypeLimits() throws Exception {
    String text = "C4 ".repeat(513).trim();
    assertEquals(
        513,
        LocalImporters.text(text.getBytes(StandardCharsets.UTF_8), "long.txt", "NOTES")
            .notes()
            .size());
    Sequence sequence = new Sequence(Sequence.PPQ, 480);
    var track = sequence.createTrack();
    for (int i = 0; i < 513; i++) add(track, i * 480L, (i + 1) * 480L, 60 + i % 12);
    var draft = LocalImporters.midi(midiBytes(sequence), "long.mid");
    assertEquals(513, draft.notes().size());
    assertEquals(129, DraftScoreBuilder.build(draft, 4, 480).parts().getFirst().measures().size());
    assertTrue(LocalImporters.wav(sineWav(48_000, 6), "long.wav").notes().size() > 0);
  }

  @Test
  void midiRetainsExactGapsAndRejectsPolyphony() throws Exception {
    Sequence sequence = new Sequence(Sequence.PPQ, 480);
    var track = sequence.createTrack();
    add(track, 0, 480, 60);
    add(track, 960, 1440, 62);
    var draft = LocalImporters.midi(midiBytes(sequence), "melody.mid");
    assertEquals("EXACT_TICKS", draft.rhythm());
    assertEquals(960, draft.notes().get(1).onsetTicks());
    assertEquals(480, draft.notes().get(1).durationTicks());
    assertEquals(
        4,
        DraftScoreBuilder.build(draft, 4, 480)
            .parts()
            .getFirst()
            .measures()
            .getFirst()
            .events()
            .size());
    add(track, 240, 720, 64);
    assertThrows(
        IllegalArgumentException.class,
        () -> LocalImporters.midi(midiBytes(sequence), "polyphonic.mid"));
  }

  @Test
  void audioPrototypeReportsEstimatesAndRejectsUnsupportedFormat() throws Exception {
    byte[] wav = sineWav();
    var draft = LocalImporters.wav(wav, "single.wav");
    assertEquals("ESTIMATED", draft.rhythm());
    assertEquals("A", draft.notes().getFirst().step().name());
    assertTrue(draft.notes().getFirst().confidence() >= .75);
    assertThrows(IllegalArgumentException.class, () -> LocalImporters.wav(wav, "../single.wav"));
    assertThrows(
        IllegalArgumentException.class, () -> LocalImporters.wav(new byte[0], "empty.wav"));
  }

  private static void add(javax.sound.midi.Track track, long start, long end, int pitch)
      throws Exception {
    var on = new ShortMessage();
    on.setMessage(ShortMessage.NOTE_ON, 0, pitch, 100);
    var off = new ShortMessage();
    off.setMessage(ShortMessage.NOTE_OFF, 0, pitch, 0);
    track.add(new MidiEvent(on, start));
    track.add(new MidiEvent(off, end));
  }

  private static byte[] midiBytes(Sequence sequence) throws Exception {
    var out = new ByteArrayOutputStream();
    MidiSystem.write(sequence, 0, out);
    return out.toByteArray();
  }

  private static byte[] sineWav() {
    return sineWav(16_000, 1);
  }

  private static byte[] sineWav(int rate, int seconds) {
    int samples = rate * seconds;
    var buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
    buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2);
    buffer.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    buffer.putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2);
    buffer.putShort((short) 2).putShort((short) 16);
    buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
    for (int i = 0; i < samples; i++)
      buffer.putShort((short) (16000 * Math.sin(2 * Math.PI * 440 * i / rate)));
    return buffer.array();
  }
}
