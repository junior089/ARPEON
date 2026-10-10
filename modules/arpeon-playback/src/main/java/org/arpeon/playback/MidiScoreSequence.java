package org.arpeon.playback;

import java.util.Map;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.arpeon.domain.instrument.InstrumentProfile;
import org.arpeon.transport.ScoreTimeline;

/** A local synthesized performance of the supported single-voice MusicXML subset. */
public final class MidiScoreSequence {
  private MidiScoreSequence() {}

  public static Sequence build(ScoreTimeline timeline) {
    return build(timeline, Map.of());
  }

  public static Sequence build(ScoreTimeline timeline, Map<String, InstrumentProfile> profiles) {
    if (timeline.score().parts().size() > 15)
      throw new IllegalArgumentException("MIDI playback supports at most 15 parts");
    try {
      var sequence = new Sequence(Sequence.PPQ, ScoreTimeline.TICKS_PER_QUARTER);
      long offset = timeline.countInTicks();
      Track timing = sequence.createTrack();
      for (var tempo : timeline.tempos()) {
        timing.add(new MidiEvent(tempoMessage(tempo.bpm()), offset + tempo.startTick()));
      }
      // Count-in uses the first score tempo, including when the score starts with a pickup.
      timing.add(new MidiEvent(tempoMessage(timeline.firstBpm()), 0));
      for (int part = 0; part < timeline.score().parts().size(); part++) {
        Track track = sequence.createTrack();
        int channel = channel(part);
        var profile = profiles.get(timeline.score().parts().get(part).id());
        add(
            track,
            ShortMessage.PROGRAM_CHANGE,
            channel,
            profile == null ? 0 : profile.midiProgram(),
            0,
            0);
        for (var note : timeline.notes()) {
          if (note.partIndex() != part) continue;
          int sounding =
              note.midiNumber() + (profile == null ? 0 : profile.soundingTranspositionSemitones());
          if (sounding < 0 || sounding > 127)
            throw new IllegalArgumentException("Sounding pitch outside MIDI range");
          add(track, ShortMessage.NOTE_ON, channel, sounding, 80, offset + note.startTick());
          add(
              track,
              ShortMessage.NOTE_OFF,
              channel,
              sounding,
              0,
              offset + note.startTick() + note.durationTicks());
        }
      }
      Track metronome = sequence.createTrack();
      for (var measure : timeline.measures()) {
        long beatTicks = 4L * ScoreTimeline.TICKS_PER_QUARTER / measure.meter().beatUnit();
        for (long beat = 0; beat < measure.durationTicks(); beat += beatTicks)
          click(metronome, offset + measure.startTick() + beat, beat == 0);
      }
      Track countIn = sequence.createTrack();
      var meter = timeline.measures().getFirst().meter();
      long beatTicks = 4L * ScoreTimeline.TICKS_PER_QUARTER / meter.beatUnit();
      for (int beat = 0; beat < meter.beats(); beat++) click(countIn, beat * beatTicks, beat == 0);
      var end = new MetaMessage();
      end.setMessage(0x2f, new byte[0], 0);
      timing.add(new MidiEvent(end, offset + timeline.totalTicks() + 1));
      return sequence;
    } catch (InvalidMidiDataException failure) {
      throw new IllegalArgumentException("Score cannot be converted to MIDI", failure);
    }
  }

  public static int channel(int partIndex) {
    if (partIndex < 0 || partIndex >= 15) throw new IllegalArgumentException("Invalid part");
    return partIndex < 9 ? partIndex : partIndex + 1;
  }

  private static MetaMessage tempoMessage(double bpm) throws InvalidMidiDataException {
    long micros = Math.round(60_000_000.0 / bpm);
    if (micros < 1 || micros > 0xFFFFFF)
      throw new IllegalArgumentException("Tempo outside MIDI range");
    byte[] data = {(byte) (micros >>> 16), (byte) (micros >>> 8), (byte) micros};
    var message = new MetaMessage();
    message.setMessage(0x51, data, data.length);
    return message;
  }

  private static void click(Track track, long tick, boolean accent)
      throws InvalidMidiDataException {
    int pitch = accent ? 76 : 77;
    add(track, ShortMessage.NOTE_ON, 9, pitch, accent ? 96 : 72, tick);
    add(track, ShortMessage.NOTE_OFF, 9, pitch, 0, tick + 30);
  }

  private static void add(Track track, int command, int channel, int data1, int data2, long tick)
      throws InvalidMidiDataException {
    var message = new ShortMessage();
    message.setMessage(command, channel, data1, data2);
    track.add(new MidiEvent(message, tick));
  }
}
