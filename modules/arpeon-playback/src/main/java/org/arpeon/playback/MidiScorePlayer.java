package org.arpeon.playback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import org.arpeon.domain.instrument.InstrumentProfile;
import org.arpeon.transport.ScoreTimeline;

/** Owns the canonical MIDI clock and local synthesizer; HTTP polling only reads its position. */
public final class MidiScorePlayer implements AutoCloseable {
  public record PartState(
      String id, String name, boolean mute, boolean solo, String instrumentId) {}

  public record State(
      String status,
      long tick,
      long totalTicks,
      int measureIndex,
      String measureNumber,
      double bpm,
      double startingBpm,
      boolean metronome,
      boolean countIn,
      int countInBeatsLeft,
      String activePartId,
      List<PartState> parts) {}

  private final ScoreTimeline timeline;
  private final Map<String, InstrumentProfile> profiles;
  private final Sequence sequence;
  private final boolean[] muted;
  private final boolean[] solo;
  private final long offset;
  private Sequencer sequencer;
  private Synthesizer synthesizer;
  private String status = "STOPPED";
  private long position;
  private double firstBpm;
  private boolean metronome;
  private boolean countIn;
  private int activePart;

  public MidiScorePlayer(ScoreTimeline timeline) {
    this(timeline, Map.of());
  }

  public MidiScorePlayer(ScoreTimeline timeline, Map<String, InstrumentProfile> profiles) {
    this.timeline = timeline;
    this.profiles = Map.copyOf(profiles);
    this.sequence = MidiScoreSequence.build(timeline, profiles);
    this.muted = new boolean[timeline.score().parts().size()];
    this.solo = new boolean[muted.length];
    this.offset = timeline.countInTicks();
    this.firstBpm = timeline.firstBpm();
  }

  public synchronized void play() {
    try {
      openIfNeeded();
      if ("STOPPED".equals(status) && position >= timeline.totalTicks()) position = 0;
      if ("STOPPED".equals(status) && position == 0)
        sequencer.setTickPosition(countIn ? 0 : offset);
      else if ("STOPPED".equals(status)) sequencer.setTickPosition(offset + position);
      sequencer.start();
      status = "PLAYING";
    } catch (MidiUnavailableException | javax.sound.midi.InvalidMidiDataException failure) {
      closeDevices();
      throw new IllegalStateException("MIDI output unavailable", failure);
    }
  }

  public synchronized void pause() {
    updatePosition();
    if (sequencer != null) sequencer.stop();
    if ("PLAYING".equals(status)) status = "PAUSED";
  }

  public synchronized void stop() {
    if (sequencer != null) {
      sequencer.stop();
      sequencer.setTickPosition(offset);
    }
    position = 0;
    status = "STOPPED";
  }

  public synchronized void seek(long tick) {
    if (tick < 0 || tick > timeline.totalTicks())
      throw new IllegalArgumentException("Seek outside score");
    position = tick;
    if (sequencer != null) sequencer.setTickPosition(offset + tick);
  }

  public synchronized void setBpm(double bpm) {
    if (!Double.isFinite(bpm) || bpm < 30 || bpm > 300)
      throw new IllegalArgumentException("BPM must be between 30 and 300");
    firstBpm = bpm;
    if (sequencer != null) sequencer.setTempoFactor((float) (bpm / timeline.firstBpm()));
  }

  public synchronized void setMetronome(boolean enabled) {
    metronome = enabled;
    applyTrackMute();
  }

  public synchronized void setCountIn(boolean enabled) {
    countIn = enabled;
  }

  public synchronized void selectPart(String id) {
    activePart = partIndex(id);
  }

  public synchronized void setMute(String id, boolean enabled) {
    muted[partIndex(id)] = enabled;
    applyTrackMute();
  }

  public synchronized void setSolo(String id, boolean enabled) {
    solo[partIndex(id)] = enabled;
    applyTrackMute();
  }

  private int partIndex(String id) {
    for (int i = 0; i < muted.length; i++)
      if (timeline.score().parts().get(i).id().equals(id)) return i;
    throw new IllegalArgumentException("Unknown part");
  }

  public synchronized State snapshot() {
    updatePosition();
    int measureIndex = timeline.measureIndexAt(position);
    var parts = new ArrayList<PartState>();
    for (int i = 0; i < muted.length; i++) {
      var part = timeline.score().parts().get(i);
      var profile = profiles.get(part.id());
      parts.add(
          new PartState(
              part.id(),
              part.name(),
              muted[i],
              solo[i],
              profile == null ? "concert" : profile.id()));
    }
    int remaining = 0;
    if ("PLAYING".equals(status) && sequencer != null && sequencer.getTickPosition() < offset) {
      long beatTicks =
          4L * ScoreTimeline.TICKS_PER_QUARTER / timeline.measures().getFirst().meter().beatUnit();
      remaining = (int) ((offset - sequencer.getTickPosition() + beatTicks - 1) / beatTicks);
    }
    return new State(
        status,
        position,
        timeline.totalTicks(),
        measureIndex,
        timeline.measures().get(measureIndex).number(),
        timeline.bpmAt(position) * firstBpm / timeline.firstBpm(),
        firstBpm,
        metronome,
        countIn,
        remaining,
        timeline.score().parts().get(activePart).id(),
        List.copyOf(parts));
  }

  private void updatePosition() {
    if (sequencer == null || !"PLAYING".equals(status)) return;
    position = Math.max(0, Math.min(timeline.totalTicks(), sequencer.getTickPosition() - offset));
    if (!sequencer.isRunning() || position >= timeline.totalTicks()) {
      status = "STOPPED";
      position = timeline.totalTicks();
    }
  }

  private void openIfNeeded()
      throws MidiUnavailableException, javax.sound.midi.InvalidMidiDataException {
    if (sequencer != null) return;
    Synthesizer synth = MidiSystem.getSynthesizer();
    Sequencer seq = MidiSystem.getSequencer(false);
    try {
      synth.open();
      seq.open();
      seq.getTransmitter().setReceiver(synth.getReceiver());
      seq.setSequence(sequence);
      seq.setTempoFactor((float) (firstBpm / timeline.firstBpm()));
      synthesizer = synth;
      sequencer = seq;
      applyTrackMute();
    } catch (MidiUnavailableException
        | javax.sound.midi.InvalidMidiDataException
        | RuntimeException failure) {
      seq.close();
      synth.close();
      throw failure;
    }
  }

  private void applyTrackMute() {
    if (sequencer == null) return;
    boolean anySolo = false;
    for (boolean enabled : solo) anySolo |= enabled;
    for (int i = 0; i < muted.length; i++) {
      boolean silence = muted[i] || (anySolo && !solo[i]);
      sequencer.setTrackMute(i + 1, silence);
      if (silence && synthesizer != null)
        synthesizer.getChannels()[MidiScoreSequence.channel(i)].allNotesOff();
    }
    sequencer.setTrackMute(muted.length + 1, !metronome);
  }

  private void closeDevices() {
    if (sequencer != null) sequencer.close();
    if (synthesizer != null) synthesizer.close();
    sequencer = null;
    synthesizer = null;
  }

  @Override
  public synchronized void close() {
    closeDevices();
  }
}
