package org.arpeon.importer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.arpeon.audio.AudioFrame;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;
import org.arpeon.dsp.pitch.PitchDetection;
import org.arpeon.dsp.pitch.YinPitchDetector;

/** Bounded, local parsers. They never infer complete notation from incomplete sources. */
public final class LocalImporters {
  public static final int MAX_TEXT_BYTES = 2 * 1_048_576;
  public static final int MAX_MIDI_BYTES = 32 * 1_048_576;
  public static final int MAX_WAV_BYTES = 32 * 1_048_576;
  public static final int MAX_DRAFT_NOTES = 8192;
  public static final int MAX_WAV_SECONDS = 120;
  private static final Pattern ENGLISH = Pattern.compile("(?i)([A-G])(bb|##|b|#)?(-1|[0-9])?");
  private static final Pattern SOLFEGE =
      Pattern.compile("(?i)(DO|RE|MI|FA|SOL|LA|SI)(bb|##|b|#)?(-1|[0-9])?");

  private LocalImporters() {}

  public static ImportDraft text(byte[] bytes, String filename, String kind) throws IOException {
    requireSize(bytes, MAX_TEXT_BYTES);
    if (!List.of("NOTES", "SOLFEGE", "CIFRA_MELODICA").contains(kind))
      throw new IllegalArgumentException("Select a supported text notation");
    String source;
    try {
      source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException failure) {
      throw new IOException("Text must be UTF-8", failure);
    }
    if (source.indexOf('\0') >= 0) throw new IllegalArgumentException("Binary input is not text");
    var notes = new ArrayList<ImportDraft.DraftNote>();
    Pattern grammar = kind.equals("NOTES") ? ENGLISH : SOLFEGE;
    for (String token : source.split("[\\s,;]+")) {
      if (token.isEmpty() || token.equals("-")) continue;
      var match = grammar.matcher(token);
      if (!match.matches())
        throw new IllegalArgumentException(
            "Unsupported or ambiguous note token: " + safeToken(token));
      String name = match.group(1).toUpperCase(Locale.ROOT);
      PitchClass step = kind.equals("NOTES") ? PitchClass.valueOf(name) : solfege(name);
      Accidental accidental =
          switch (match.group(2) == null ? "" : match.group(2)) {
            case "bb" -> Accidental.DOUBLE_FLAT;
            case "b" -> Accidental.FLAT;
            case "#" -> Accidental.SHARP;
            case "##" -> Accidental.DOUBLE_SHARP;
            default -> Accidental.NATURAL;
          };
      Integer octave = match.group(3) == null ? null : Integer.valueOf(match.group(3));
      if (octave != null) new Pitch(step, accidental, octave);
      notes.add(new ImportDraft.DraftNote(step, accidental, octave, null, null, 1));
      if (notes.size() > MAX_DRAFT_NOTES)
        throw new IllegalArgumentException("Too many notes (maximum " + MAX_DRAFT_NOTES + ")");
    }
    if (notes.isEmpty()) throw new IllegalArgumentException("No notes found");
    var warnings = new ArrayList<String>();
    warnings.add("A fonte não informa duração; a sequência ainda não é uma partitura.");
    if (notes.stream().anyMatch(note -> note.octave() == null))
      warnings.add("Oitava ausente; escolha uma oitava antes de criar a partitura.");
    warnings.add("Separadores não são interpretados como pausas, compassos ou repetições.");
    return new ImportDraft(kind, filename, sha(bytes), "local-text-1", "NONE", notes, warnings);
  }

  public static ImportDraft midi(byte[] bytes, String filename) throws IOException {
    requireSize(bytes, MAX_MIDI_BYTES);
    if (!filename.toLowerCase(Locale.ROOT).matches("[^/\\\\:]+\\.(mid|midi)"))
      throw new IllegalArgumentException("Use a .mid or .midi filename without a path");
    Sequence sequence;
    try {
      sequence = MidiSystem.getSequence(new ByteArrayInputStream(bytes));
    } catch (InvalidMidiDataException failure) {
      throw new IOException("Invalid MIDI file", failure);
    }
    if (sequence.getDivisionType() != Sequence.PPQ || sequence.getResolution() < 1)
      throw new IllegalArgumentException("Only PPQ MIDI timing is supported");
    if (sequence.getTracks().length > 128)
      throw new IllegalArgumentException("Too many MIDI tracks");
    record Timed(long tick, int channel, int pitch, boolean on) {}
    var messages = new ArrayList<Timed>();
    int usedChannel = -1;
    for (var track : sequence.getTracks()) {
      if (track.size() > 131_072)
        throw new IllegalArgumentException("MIDI track exceeds event limit");
      for (int i = 0; i < track.size(); i++) {
        MidiEvent event = track.get(i);
        if (!(event.getMessage() instanceof ShortMessage shortMessage)) continue;
        int command = shortMessage.getCommand();
        if (command == ShortMessage.NOTE_ON || command == ShortMessage.NOTE_OFF) {
          if (shortMessage.getChannel() == 9)
            throw new IllegalArgumentException("Percussion MIDI is not a melody");
          if (usedChannel >= 0 && shortMessage.getChannel() != usedChannel)
            throw new IllegalArgumentException("Multiple MIDI channels need separate part review");
          usedChannel = shortMessage.getChannel();
          messages.add(
              new Timed(
                  event.getTick(),
                  shortMessage.getChannel(),
                  shortMessage.getData1(),
                  command == ShortMessage.NOTE_ON && shortMessage.getData2() > 0));
        }
      }
    }
    messages.sort(Comparator.comparingLong(Timed::tick).thenComparing(Timed::on));
    if (messages.size() > 32_768) throw new IllegalArgumentException("Too many MIDI note events");
    var open = new TreeMap<Integer, Long>();
    var notes = new ArrayList<ImportDraft.DraftNote>();
    for (var message : messages) {
      int key = message.channel() * 128 + message.pitch();
      if (message.on()) {
        if (open.putIfAbsent(key, message.tick()) != null)
          throw new IllegalArgumentException("Overlapping same-pitch MIDI notes are ambiguous");
      } else {
        Long start = open.remove(key);
        if (start == null || start >= message.tick())
          throw new IllegalArgumentException("Unpaired or zero-length MIDI note");
        long onset = scaleTick(start, sequence.getResolution());
        long end = scaleTick(message.tick(), sequence.getResolution());
        Pitch pitch = Pitch.fromMidiNumber(message.pitch());
        notes.add(
            new ImportDraft.DraftNote(
                pitch.pitchClass(), pitch.accidental(), pitch.octave(), onset, end - onset, 1));
        if (notes.size() > MAX_DRAFT_NOTES)
          throw new IllegalArgumentException("Too many notes (maximum " + MAX_DRAFT_NOTES + ")");
      }
    }
    if (!open.isEmpty()) throw new IllegalArgumentException("Unclosed MIDI note");
    notes.sort(Comparator.comparingLong(ImportDraft.DraftNote::onsetTicks));
    long end = 0;
    for (var note : notes) {
      if (note.onsetTicks() < end)
        throw new IllegalArgumentException("Polyphonic MIDI cannot be represented as one voice");
      end = note.onsetTicks() + note.durationTicks();
    }
    if (notes.isEmpty()) throw new IllegalArgumentException("No MIDI melody notes found");
    return new ImportDraft(
        "MIDI",
        filename,
        sha(bytes),
        "local-midi-1",
        "EXACT_TICKS",
        notes,
        List.of(
            "Alturas e durações vêm dos eventos MIDI; grafia enarmônica, articulação e intenção de"
                + " pauta exigem revisão.",
            "O andamento e o pedal MIDI não são importados; a reprodução usa 120 BPM até ajuste"
                + " manual."));
  }

  private static long scaleTick(long tick, int resolution) {
    if (tick < 0 || tick > 50_000_000 || tick * 480 % resolution != 0)
      throw new IllegalArgumentException("MIDI ticks cannot be represented exactly at 480 PPQ");
    return tick * 480 / resolution;
  }

  public static ImportDraft wav(byte[] bytes, String filename) throws IOException {
    requireSize(bytes, MAX_WAV_BYTES);
    if (!filename.toLowerCase(Locale.ROOT).matches("[^/\\\\:]+\\.wav"))
      throw new IllegalArgumentException("Use a .wav filename without a path");
    try (var stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
      AudioFormat format = stream.getFormat();
      int rate = Math.round(format.getSampleRate());
      if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
          || format.getChannels() != 1
          || format.getSampleSizeInBits() != 16
          || format.isBigEndian()
          || (rate != 16000 && rate != 48000)
          || stream.getFrameLength() < 1
          || stream.getFrameLength() > rate * (long) MAX_WAV_SECONDS)
        throw new IllegalArgumentException(
            "Prototype accepts mono 16-bit PCM WAV at 16 or 48 kHz, up to 120 seconds");
      byte[] pcm = stream.readNBytes(rate * MAX_WAV_SECONDS * 2 + 1);
      if (pcm.length > rate * MAX_WAV_SECONDS * 2 || pcm.length % 2 != 0)
        throw new IllegalArgumentException("WAV PCM length exceeds limit");
      int stride = rate / 16000;
      var sample = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
      float[] mono = new float[sample.remaining() / stride];
      for (int i = 0; i < mono.length; i++) mono[i] = sample.get(i * stride) / 32768f;
      var detector = new YinPitchDetector();
      var notes = new ArrayList<ImportDraft.DraftNote>();
      int previous = -1;
      int start = 0;
      double confidence = 0;
      int count = 0;
      for (int offset = 0; offset + 2048 <= mono.length; offset += 1024) {
        float[] frame = java.util.Arrays.copyOfRange(mono, offset, offset + 2048);
        PitchDetection detected =
            detector.detect(new AudioFrame(frame, 16000, java.time.Instant.EPOCH, offset));
        int current =
            detected.state() == PitchDetection.State.DETECTED && detected.confidence() >= .75
                ? (int) Math.round(69 + 12 * Math.log(detected.frequencyHz() / 440) / Math.log(2))
                : -1;
        if (current < 0 || current > 127) current = -1;
        if (current != previous) {
          if (previous >= 0 && count >= 2)
            notes.add(audioNote(previous, start, offset, confidence / count));
          if (notes.size() > MAX_DRAFT_NOTES)
            throw new IllegalArgumentException("Too many detected notes");
          previous = current;
          start = offset;
          confidence = 0;
          count = 0;
        }
        if (current >= 0) {
          confidence += detected.confidence();
          count++;
        }
      }
      if (previous >= 0 && count >= 2)
        notes.add(audioNote(previous, start, mono.length, confidence / count));
      if (notes.size() > MAX_DRAFT_NOTES)
        throw new IllegalArgumentException("Too many detected notes");
      if (notes.isEmpty())
        throw new IllegalArgumentException("No stable monophonic pitch detected");
      return new ImportDraft(
          "WAV",
          filename,
          sha(bytes),
          "local-wav-prototype-1",
          "ESTIMATED",
          notes,
          List.of(
              "Protótipo monofônico: alturas e inícios são estimativas; confira cada nota.",
              "Durações propostas assumem 120 BPM e não são ritmo exato. A partitura requer uma"
                  + " decisão explícita de quantização."));
    } catch (UnsupportedAudioFileException failure) {
      throw new IOException("Unsupported WAV file", failure);
    }
  }

  private static ImportDraft.DraftNote audioNote(int midi, int start, int end, double confidence) {
    Pitch pitch = Pitch.fromMidiNumber(midi);
    long onset = Math.round(start * 960.0 / 16000);
    long length = Math.max(1, Math.round((end - start) * 960.0 / 16000));
    return new ImportDraft.DraftNote(
        pitch.pitchClass(), pitch.accidental(), pitch.octave(), onset, length, confidence);
  }

  private static PitchClass solfege(String value) {
    return switch (value) {
      case "DO" -> PitchClass.C;
      case "RE" -> PitchClass.D;
      case "MI" -> PitchClass.E;
      case "FA" -> PitchClass.F;
      case "SOL" -> PitchClass.G;
      case "LA" -> PitchClass.A;
      case "SI" -> PitchClass.B;
      default -> throw new IllegalArgumentException("Invalid solfège note");
    };
  }

  private static String safeToken(String token) {
    return token.length() > 32 ? token.substring(0, 32) + "…" : token;
  }

  private static void requireSize(byte[] bytes, int max) {
    if (bytes == null || bytes.length == 0 || bytes.length > max)
      throw new IllegalArgumentException("Import size must be between 1 and " + max + " bytes");
  }

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
