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
  /** End tick is exclusive; indexes refer to performed measure occurrences. */
  public record LoopRange(
      long startTick, long endTick, int startMeasureIndex, int endMeasureIndex) {}

  public record PartState(
      String id,
      String name,
      boolean mute,
      boolean solo,
      String instrumentId,
      double volume,
      double pan,
      double effectiveGain,
      String soundStatus,
      String soundError) {}

  public record ChannelSettings(double volume, double pan, boolean mute, boolean solo) {
    public ChannelSettings {
      level(volume);
      if (!Double.isFinite(pan) || pan < -1 || pan > 1)
        throw new IllegalArgumentException("Pan must be between -1 and 1");
    }
  }

  public record Settings(
      double master,
      String activePartId,
      Map<String, ChannelSettings> channels,
      double bpm,
      boolean metronome,
      boolean countIn,
      LoopRange loop) {
    public Settings {
      level(master);
      channels = Map.copyOf(channels);
      if (!Double.isFinite(bpm) || bpm <= 0 || bpm > 1000)
        throw new IllegalArgumentException("Invalid study tempo");
    }
  }

  public record State(
      String status,
      long tick,
      long audibleTick,
      double audibleBpm,
      double audioLatencyMs,
      long totalTicks,
      int measureIndex,
      String measureNumber,
      double bpm,
      double startingBpm,
      boolean metronome,
      boolean countIn,
      int countInBeatsLeft,
      String activePartId,
      List<PartState> parts,
      LoopRange loop,
      double master) {}

  private final ScoreTimeline timeline;
  private final Map<String, InstrumentProfile> profiles;
  private Sequence sequence;
  private final boolean[] muted;
  private final boolean[] solo;
  private final double[] volumes;
  private final double[] pans;
  private final String[] soundStatuses;
  private final String[] soundErrors;
  private double master = 1;
  private MixerReceiver mixer;
  private final long offset;
  private Sequencer sequencer;
  private Synthesizer synthesizer;
  private String status = "STOPPED";
  private long position;
  private double firstBpm;
  private boolean metronome;
  private boolean countIn;
  private int activePart;
  private LoopRange loop;
  private boolean loopHasWrapped;

  public MidiScorePlayer(ScoreTimeline timeline) {
    this(timeline, Map.of());
  }

  public MidiScorePlayer(ScoreTimeline timeline, Map<String, InstrumentProfile> profiles) {
    this.timeline = timeline;
    this.profiles = Map.copyOf(profiles);
    this.sequence = MidiScoreSequence.build(timeline, profiles);
    this.muted = new boolean[timeline.score().parts().size()];
    this.solo = new boolean[muted.length];
    this.volumes = new double[muted.length];
    java.util.Arrays.fill(volumes, 1);
    this.pans = new double[muted.length];
    this.soundStatuses = new String[muted.length];
    java.util.Arrays.fill(soundStatuses, "NOT_LOADED");
    this.soundErrors = new String[muted.length];
    java.util.Arrays.fill(soundErrors, "");
    this.offset = timeline.countInTicks();
    this.firstBpm = timeline.firstBpm();
  }

  /** Test seam: an already-open software sequencer without a synthesizer or audio output. */
  MidiScorePlayer(
      ScoreTimeline timeline, Map<String, InstrumentProfile> profiles, Sequencer clock) {
    this(timeline, profiles);
    try {
      clock.setSequence(sequence);
      sequencer = clock;
      applyTrackMute();
    } catch (javax.sound.midi.InvalidMidiDataException invalid) {
      throw new IllegalArgumentException("Invalid playback sequence", invalid);
    }
  }

  MidiScorePlayer(
      ScoreTimeline timeline,
      Map<String, InstrumentProfile> profiles,
      Sequencer clock,
      Synthesizer output)
      throws MidiUnavailableException {
    this(timeline, profiles, clock);
    synthesizer = output;
    mixer = new MixerReceiver(output.getReceiver());
    clock.getTransmitter().setReceiver(mixer);
    loadSounds();
    applyMixer();
    applyTrackMute();
  }

  public synchronized void play() {
    if (status.equals("PLAYING")) return;
    try {
      openIfNeeded();
      if (loop != null && (position < loop.startTick() || position >= loop.endTick()))
        position = loop.startTick();
      if ("STOPPED".equals(status) && position >= timeline.totalTicks()) position = 0;
      if ("STOPPED".equals(status) && position == 0)
        sequencer.setTickPosition(countIn ? 0 : offset);
      else if ("STOPPED".equals(status)) sequencer.setTickPosition(offset + position);
      sequencer.start();
      status = "PLAYING";
      resumeSustainedNotes();
    } catch (MidiUnavailableException | javax.sound.midi.InvalidMidiDataException failure) {
      closeDevices();
      throw new IllegalStateException("MIDI output unavailable", failure);
    }
  }

  public synchronized void pause() {
    if (sequencer != null) {
      sequencer.stop();
      position = Math.max(0, Math.min(timeline.totalTicks(), sequencer.getTickPosition() - offset));
      if (synthesizer != null) silence();
    }
    if ("PLAYING".equals(status)) status = "PAUSED";
  }

  public synchronized void stop() {
    if (sequencer != null) {
      sequencer.stop();
      sequencer.setTickPosition(offset + (loop == null ? 0 : loop.startTick()));
    }
    silence();
    position = loop == null ? 0 : loop.startTick();
    loopHasWrapped = false;
    status = "STOPPED";
  }

  public synchronized void seek(long tick) {
    if (tick < 0 || tick > timeline.totalTicks())
      throw new IllegalArgumentException("Seek outside score");
    if (loop != null && (tick < loop.startTick() || tick >= loop.endTick()))
      throw new IllegalArgumentException(
          "A posição está fora do trecho A–B. Desative a repetição para navegar fora dele.");
    silence();
    loopHasWrapped = false;
    position = tick;
    if (sequencer != null) sequencer.setTickPosition(offset + tick);
    if (status.equals("PLAYING")) resumeSustainedNotes();
  }

  private void resumeSustainedNotes() {
    resumeSustainedNotes(-1);
  }

  private void resumeSustainedNotes(int selectedPart) {
    if (synthesizer == null || mixer == null || loop != null && position == loop.startTick())
      return;
    for (var note : timeline.notes()) {
      if (selectedPart >= 0 && note.partIndex() != selectedPart) continue;
      if (note.startTick() >= position
          || note.startTick() + note.durationTicks() <= position
          || effectiveGain(note.partIndex()) == 0) continue;
      var profile = profiles.get(timeline.score().parts().get(note.partIndex()).id());
      int pitch =
          note.midiNumber() + (profile == null ? 0 : profile.soundingTranspositionSemitones());
      var channel = synthesizer.getChannels()[MidiScoreSequence.channel(note.partIndex())];
      if (channel != null) channel.noteOn(pitch, 80);
    }
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

  public synchronized void setLoop(int startMeasureIndex, int endMeasureIndex, boolean enabled) {
    if (startMeasureIndex < 0
        || endMeasureIndex < startMeasureIndex
        || endMeasureIndex >= timeline.measures().size())
      throw new IllegalArgumentException("Escolha A e B na ordem da execução da partitura.");
    var first = timeline.measures().get(startMeasureIndex);
    var last = timeline.measures().get(endMeasureIndex);
    LoopRange selected =
        enabled
            ? new LoopRange(
                first.startTick(),
                last.startTick() + last.durationTicks(),
                startMeasureIndex,
                endMeasureIndex)
            : null;
    Sequence next = MidiScoreSequence.build(timeline, profiles, selected);
    updatePosition();
    boolean playing = status.equals("PLAYING");
    if (sequencer != null) sequencer.stop();
    silence();
    loop = selected;
    loopHasWrapped = false;
    sequence = next;
    if (loop != null && (position < loop.startTick() || position >= loop.endTick()))
      position = loop.startTick();
    if (sequencer != null) {
      try {
        sequencer.setSequence(sequence);
      } catch (javax.sound.midi.InvalidMidiDataException invalid) {
        status = "STOPPED";
        throw new IllegalStateException("Não foi possível configurar a repetição A–B.", invalid);
      }
      sequencer.setTempoFactor((float) (firstBpm / timeline.firstBpm()));
      applyLoop();
      applyTrackMute();
      sequencer.setTickPosition(offset + position);
      if (playing) {
        sequencer.start();
        resumeSustainedNotes();
      }
    }
  }

  private void applyLoop() {
    if (sequencer == null) return;
    sequencer.setLoopCount(0);
    sequencer.setLoopStartPoint(0);
    sequencer.setLoopEndPoint(-1);
    if (loop != null) {
      sequencer.setLoopStartPoint(offset + loop.startTick());
      sequencer.setLoopEndPoint(offset + loop.endTick() - 1);
      sequencer.setLoopCount(Sequencer.LOOP_CONTINUOUSLY);
    }
  }

  private void silence() {
    if (synthesizer != null)
      for (var channel : synthesizer.getChannels())
        if (channel != null) {
          channel.controlChange(64, 0);
          channel.allSoundOff();
        }
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

  private static void level(double value) {
    if (!Double.isFinite(value) || value < 0 || value > 1)
      throw new IllegalArgumentException("Volume must be between 0 and 1");
  }

  public synchronized void setVolume(String id, double volume) {
    level(volume);
    volumes[partIndex(id)] = volume;
    applyMixer();
  }

  public synchronized void setPan(String id, double pan) {
    if (!Double.isFinite(pan) || pan < -1 || pan > 1)
      throw new IllegalArgumentException("Pan must be between -1 and 1");
    pans[partIndex(id)] = pan;
    applyMixer();
  }

  public synchronized void setMaster(double volume) {
    level(volume);
    master = volume;
    applyMixer();
  }

  private double effectiveGain(int part) {
    boolean anySolo = false;
    for (boolean enabled : solo) anySolo |= enabled;
    return muted[part] || (anySolo && !solo[part]) || soundStatuses[part].equals("ERROR")
        ? 0
        : master * volumes[part];
  }

  private void applyMixer() {
    if (mixer == null) return;
    mixer.setMaster(master);
    for (int i = 0; i < volumes.length; i++)
      mixer.setChannel(MidiScoreSequence.channel(i), volumes[i], pans[i]);
  }

  public synchronized Settings settings() {
    var channels = new java.util.LinkedHashMap<String, ChannelSettings>();
    for (int i = 0; i < volumes.length; i++)
      channels.put(
          timeline.score().parts().get(i).id(),
          new ChannelSettings(volumes[i], pans[i], muted[i], solo[i]));
    return new Settings(
        master,
        timeline.score().parts().get(activePart).id(),
        channels,
        firstBpm,
        metronome,
        countIn,
        loop);
  }

  public synchronized void restore(Settings settings) {
    if (settings == null) return;
    setMaster(settings.master());
    firstBpm = settings.bpm();
    if (sequencer != null) sequencer.setTempoFactor((float) (firstBpm / timeline.firstBpm()));
    setMetronome(settings.metronome());
    setCountIn(settings.countIn());
    for (var entry : settings.channels().entrySet()) {
      if (timeline.score().parts().stream().noneMatch(p -> p.id().equals(entry.getKey()))) continue;
      var value = entry.getValue();
      setVolume(entry.getKey(), value.volume());
      setPan(entry.getKey(), value.pan());
      setMute(entry.getKey(), value.mute());
      setSolo(entry.getKey(), value.solo());
    }
    if (timeline.score().parts().stream().anyMatch(p -> p.id().equals(settings.activePartId())))
      selectPart(settings.activePartId());
    if (settings.loop() != null && settings.loop().endMeasureIndex() < timeline.measures().size())
      setLoop(settings.loop().startMeasureIndex(), settings.loop().endMeasureIndex(), true);
  }

  public synchronized void preset(String preset) {
    if (!java.util.Set.of("ensemble", "practice", "listen", "reset").contains(preset))
      throw new IllegalArgumentException("Unknown rehearsal preset");
    for (int i = 0; i < volumes.length; i++) {
      muted[i] = preset.equals("practice") && i == activePart;
      solo[i] = preset.equals("listen") && i == activePart;
      if (preset.equals("reset")) {
        volumes[i] = 1;
        pans[i] = 0;
      }
    }
    if (preset.equals("reset")) master = 1;
    applyMixer();
    applyTrackMute();
  }

  public synchronized void retrySounds() {
    if (synthesizer != null) {
      loadSounds();
      applyTrackMute();
    }
  }

  private void loadSounds() {
    for (int i = 0; i < volumes.length; i++) {
      var profile = profiles.get(timeline.score().parts().get(i).id());
      int program = profile == null ? 0 : profile.midiProgram();
      soundStatuses[i] = "ERROR";
      soundErrors[i] =
          "Timbre indisponível no sintetizador local. Escolha outro instrumento ou tente"
              + " novamente.";
      for (var instrument : synthesizer.getAvailableInstruments()) {
        if (instrument.getPatch().getBank() != 0 || instrument.getPatch().getProgram() != program)
          continue;
        try {
          if (synthesizer.loadInstrument(instrument)) {
            soundStatuses[i] = "READY";
            soundErrors[i] = "";
          }
        } catch (IllegalArgumentException failure) {
          soundErrors[i] = "Não foi possível carregar o timbre: " + failure.getMessage();
        }
        break;
      }
    }
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
              profile == null ? "concert" : profile.id(),
              volumes[i],
              pans[i],
              effectiveGain(i),
              soundStatuses[i],
              soundErrors[i]));
    }
    int remaining = 0;
    if ("PLAYING".equals(status) && sequencer != null && sequencer.getTickPosition() < offset) {
      long beatTicks =
          4L * ScoreTimeline.TICKS_PER_QUARTER / timeline.measures().getFirst().meter().beatUnit();
      remaining = (int) ((offset - sequencer.getTickPosition() + beatTicks - 1) / beatTicks);
    }
    long latencyMicros = synthesizer == null ? 0 : Math.max(0, synthesizer.getLatency());
    long audible =
        "PLAYING".equals(status)
            ? audibleTick(timeline, position, latencyMicros, firstBpm / timeline.firstBpm())
            : position;
    if (loop != null && status.equals("PLAYING") && remaining == 0) {
      double startSeconds = timeline.secondsAt(loop.startTick());
      double endSeconds = timeline.secondsAt(loop.endTick());
      double delayed =
          timeline.secondsAt(position)
              - latencyMicros / 1_000_000.0 * firstBpm / timeline.firstBpm();
      if (loopHasWrapped && delayed < startSeconds)
        delayed = endSeconds - ((startSeconds - delayed) % (endSeconds - startSeconds));
      audible =
          Math.max(
              loop.startTick(),
              Math.min(
                  loop.endTick() - 1, timeline.tickAtSeconds(Math.max(startSeconds, delayed))));
    }
    return new State(
        status,
        position,
        audible,
        timeline.bpmAt(audible) * firstBpm / timeline.firstBpm(),
        latencyMicros / 1000.0,
        timeline.totalTicks(),
        measureIndex,
        timeline.measures().get(measureIndex).number(),
        timeline.bpmAt(position) * firstBpm / timeline.firstBpm(),
        firstBpm,
        metronome,
        countIn,
        remaining,
        timeline.score().parts().get(activePart).id(),
        List.copyOf(parts),
        loop,
        master);
  }

  static long audibleTick(
      ScoreTimeline timeline, long tick, long latencyMicros, double tempoFactor) {
    double scoreSeconds =
        timeline.secondsAt(tick) - Math.max(0, latencyMicros) / 1_000_000.0 * tempoFactor;
    return timeline.tickAtSeconds(Math.max(0, scoreSeconds));
  }

  private void updatePosition() {
    if (sequencer == null || !"PLAYING".equals(status)) return;
    long nextPosition =
        Math.max(0, Math.min(timeline.totalTicks(), sequencer.getTickPosition() - offset));
    if (loop != null && nextPosition < position) loopHasWrapped = true;
    position = nextPosition;
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
      mixer = new MixerReceiver(synth.getReceiver());
      seq.getTransmitter().setReceiver(mixer);
      seq.setSequence(sequence);
      seq.setTempoFactor((float) (firstBpm / timeline.firstBpm()));
      synthesizer = synth;
      sequencer = seq;
      loadSounds();
      applyMixer();
      applyLoop();
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
      boolean silence = muted[i] || (anySolo && !solo[i]) || soundStatuses[i].equals("ERROR");
      boolean wasSilent = sequencer.getTrackMute(i + 1);
      sequencer.setTrackMute(i + 1, silence);
      if (wasSilent && !silence && status.equals("PLAYING")) {
        updatePosition();
        resumeSustainedNotes(i);
      }
      if (silence && synthesizer != null) {
        var channel = synthesizer.getChannels()[MidiScoreSequence.channel(i)];
        if (channel != null) {
          channel.controlChange(64, 0);
          channel.allSoundOff();
        }
      }
    }
    sequencer.setTrackMute(muted.length + 1, !metronome);
  }

  private void closeDevices() {
    if (sequencer != null) sequencer.close();
    if (synthesizer != null) synthesizer.close();
    sequencer = null;
    synthesizer = null;
    mixer = null;
    java.util.Arrays.fill(soundStatuses, "NOT_LOADED");
  }

  @Override
  public synchronized void close() {
    closeDevices();
  }
}
