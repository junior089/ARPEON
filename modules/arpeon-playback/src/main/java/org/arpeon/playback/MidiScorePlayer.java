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
      String soundError,
      String soundName) {}

  public record ChannelSettings(double volume, double pan, boolean mute, boolean solo) {
    public ChannelSettings {
      level(volume);
      if (!Double.isFinite(pan) || pan < -1 || pan > 1)
        throw new IllegalArgumentException("Pan must be between -1 and 1");
    }
  }

  public record MixPreset(
      String id, String name, double master, Map<String, ChannelSettings> channels) {
    public MixPreset {
      if (id == null
          || !id.matches("[0-9a-f-]{36}")
          || name == null
          || name.isBlank()
          || name.strip().length() > 80
          || name.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Use um nome de preset de até 80 caracteres.");
      name = name.strip();
      level(master);
      channels = Map.copyOf(channels);
      if (channels.size() > 15) throw new IllegalArgumentException("Too many preset channels");
    }
  }

  public record StudyMarker(String id, String name, int startMeasureIndex, int endMeasureIndex) {
    public StudyMarker {
      if (id == null
          || !id.matches("[0-9a-f-]{36}")
          || name == null
          || name.isBlank()
          || name.strip().length() > 80
          || name.codePoints().anyMatch(Character::isISOControl)
          || startMeasureIndex < 0
          || endMeasureIndex < startMeasureIndex)
        throw new IllegalArgumentException("Nome ou intervalo de estudo inválido.");
      name = name.strip();
    }
  }

  public record TempoPractice(boolean enabled, int stepBpm, double targetBpm, int loopsPerStep) {
    public TempoPractice {
      if (stepBpm < 1
          || stepBpm > 20
          || !Double.isFinite(targetBpm)
          || targetBpm < 30
          || targetBpm > 300
          || loopsPerStep < 1
          || loopsPerStep > 20)
        throw new IllegalArgumentException(
            "Use incremento de 1–20 BPM, meta de 30–300 BPM e 1–20 voltas.");
    }

    static TempoPractice disabled() {
      return new TempoPractice(false, 5, 180, 2);
    }
  }

  public record Settings(
      double master,
      String activePartId,
      Map<String, ChannelSettings> channels,
      double bpm,
      boolean metronome,
      boolean countIn,
      LoopRange loop,
      List<MixPreset> mixPresets,
      List<StudyMarker> markers,
      TempoPractice tempoPractice,
      String soundBankId) {
    public Settings(
        double master,
        String activePartId,
        Map<String, ChannelSettings> channels,
        double bpm,
        boolean metronome,
        boolean countIn,
        LoopRange loop) {
      this(
          master,
          activePartId,
          channels,
          bpm,
          metronome,
          countIn,
          loop,
          List.of(),
          List.of(),
          TempoPractice.disabled(),
          "system");
    }

    public Settings {
      level(master);
      channels = Map.copyOf(channels);
      mixPresets = mixPresets == null ? List.of() : List.copyOf(mixPresets);
      markers = markers == null ? List.of() : List.copyOf(markers);
      tempoPractice = tempoPractice == null ? TempoPractice.disabled() : tempoPractice;
      soundBankId = soundBankId == null ? "system" : soundBankId;
      if (!soundBankId.equals("system") && !soundBankId.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid sound bank");
      if (markers.size() > 24
          || markers.stream().map(StudyMarker::id).distinct().count() != markers.size())
        throw new IllegalArgumentException("Limite de 24 trechos de estudo.");
      if (mixPresets.size() > 12
          || mixPresets.stream().map(MixPreset::id).distinct().count() != mixPresets.size())
        throw new IllegalArgumentException("Limite de 12 presets por composição.");
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
      double master,
      List<MixPreset> mixPresets,
      List<StudyMarker> markers,
      TempoPractice tempoPractice,
      int completedLoops,
      String soundBankId,
      String soundBankName,
      String soundBankError) {}

  private final ScoreTimeline timeline;
  private final Map<String, InstrumentProfile> profiles;
  private Sequence sequence;
  private final boolean[] muted;
  private final boolean[] solo;
  private final double[] volumes;
  private final double[] pans;
  private final String[] soundStatuses;
  private final String[] soundErrors;
  private final String[] soundNames;
  private javax.sound.midi.Soundbank soundBank;
  private String soundBankId = "system";
  private String soundBankName = "Banco do sistema";
  private String soundBankError = "";
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
  private final List<MixPreset> mixPresets = new ArrayList<>();
  private final List<StudyMarker> markers = new ArrayList<>();
  private TempoPractice tempoPractice = TempoPractice.disabled();
  private int completedLoops;
  private int automaticPreferenceRevision;

  public synchronized int automaticPreferenceRevision() {
    return automaticPreferenceRevision;
  }

  private javax.sound.midi.MetaEventListener loopListener;
  private boolean skipInitialLoopMarker;

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
    this.soundNames = new String[muted.length];
    java.util.Arrays.fill(soundNames, "");
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
      skipInitialLoopMarker =
          loop != null && sequencer.getTickPosition() <= offset + loop.startTick();
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
    completedLoops = 0;
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
    skipInitialLoopMarker = loop != null && tick == loop.startTick();
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
    if (tempoPractice.enabled() && bpm >= tempoPractice.targetBpm())
      tempoPractice =
          new TempoPractice(
              false,
              tempoPractice.stepBpm(),
              tempoPractice.targetBpm(),
              tempoPractice.loopsPerStep());
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
    completedLoops = 0;
    if (loop == null)
      tempoPractice =
          new TempoPractice(
              false,
              tempoPractice.stepBpm(),
              tempoPractice.targetBpm(),
              tempoPractice.loopsPerStep());
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
      skipInitialLoopMarker = loop != null && position == loop.startTick();
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
    if (loopListener != null) sequencer.removeMetaEventListener(loopListener);
    Sequence expected = sequence;
    loopListener =
        message -> {
          if (message.getType() == 0x7f
              && java.util.Arrays.equals(message.getData(), MidiScoreSequence.LOOP_MARKER))
            completedLoop(expected);
        };
    sequencer.addMetaEventListener(loopListener);
  }

  private synchronized void completedLoop(Sequence expected) {
    if (sequence != expected || loop == null || !status.equals("PLAYING")) return;
    if (skipInitialLoopMarker) {
      skipInitialLoopMarker = false;
      return;
    }
    completedLoops++;
    if (tempoPractice.enabled() && completedLoops % tempoPractice.loopsPerStep() == 0) {
      automaticPreferenceRevision++;
      setBpm(Math.min(tempoPractice.targetBpm(), firstBpm + tempoPractice.stepBpm()));
      if (firstBpm >= tempoPractice.targetBpm())
        tempoPractice =
            new TempoPractice(
                false,
                tempoPractice.stepBpm(),
                tempoPractice.targetBpm(),
                tempoPractice.loopsPerStep());
    }
  }

  public synchronized void setTempoPractice(TempoPractice settings) {
    if (settings.enabled() && (loop == null || settings.targetBpm() <= firstBpm))
      throw new IllegalArgumentException("Ative A–B e escolha uma meta acima do andamento atual.");
    tempoPractice = settings;
    completedLoops = 0;
  }

  public synchronized void saveMarker(String name, int start, int end) {
    if (end >= timeline.measures().size())
      throw new IllegalArgumentException("Trecho fora da partitura.");
    if (markers.size() >= 24) throw new IllegalArgumentException("Limite de 24 trechos de estudo.");
    var marker = new StudyMarker(java.util.UUID.randomUUID().toString(), name, start, end);
    if (markers.stream().anyMatch(m -> m.name().equalsIgnoreCase(marker.name())))
      throw new IllegalArgumentException("Já existe um trecho com esse nome.");
    markers.add(marker);
  }

  public synchronized void deleteMarker(String id) {
    if (!markers.removeIf(m -> m.id().equals(id)))
      throw new IllegalArgumentException("Trecho não encontrado.");
  }

  public synchronized void studyMarker(String id) {
    var marker =
        markers.stream()
            .filter(m -> m.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Trecho não encontrado."));
    setLoop(marker.startMeasureIndex(), marker.endMeasureIndex(), true);
    seek(loop.startTick());
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
    if (!status.equals("PLAYING")) mixer.finishRamps();
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
        loop,
        mixPresets,
        markers,
        tempoPractice,
        soundBankId);
  }

  public synchronized void restore(Settings settings) {
    if (settings == null) return;
    if (!soundBankId.equals(settings.soundBankId())) {
      soundBank = null;
      soundBankName =
          settings.soundBankId().equals("system") ? "Banco do sistema" : "Banco indisponível";
      soundBankError =
          settings.soundBankId().equals("system")
              ? ""
              : "Banco precisa ser carregado neste computador.";
    }
    soundBankId = settings.soundBankId();
    mixPresets.clear();
    mixPresets.addAll(settings.mixPresets());
    markers.clear();
    settings.markers().stream()
        .filter(m -> m.endMeasureIndex() < timeline.measures().size())
        .forEach(markers::add);
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
    var practice = settings.tempoPractice();
    tempoPractice =
        new TempoPractice(
            practice.enabled() && loop != null && practice.targetBpm() > firstBpm,
            practice.stepBpm(),
            practice.targetBpm(),
            practice.loopsPerStep());
    completedLoops = 0;
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

  public synchronized void saveMixPreset(String id, String name) {
    String key = id.isEmpty() ? java.util.UUID.randomUUID().toString() : id;
    if (!id.isEmpty() && mixPresets.stream().noneMatch(p -> p.id().equals(id)))
      throw new IllegalArgumentException("Preset não encontrado.");
    if (id.isEmpty() && mixPresets.size() >= 12)
      throw new IllegalArgumentException("Limite de 12 presets por composição.");
    var settings = settings();
    var next = new MixPreset(key, name, master, settings.channels());
    if (mixPresets.stream()
        .anyMatch(p -> !p.id().equals(key) && p.name().equalsIgnoreCase(next.name())))
      throw new IllegalArgumentException("Já existe um preset com esse nome.");
    mixPresets.removeIf(p -> p.id().equals(key));
    mixPresets.add(next);
  }

  public synchronized void deleteMixPreset(String id) {
    if (!mixPresets.removeIf(p -> p.id().equals(id)))
      throw new IllegalArgumentException("Preset não encontrado.");
  }

  public synchronized void applyMixPreset(String id) {
    var preset =
        mixPresets.stream()
            .filter(p -> p.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Preset não encontrado."));
    master = preset.master();
    for (int i = 0; i < volumes.length; i++) {
      var channel = preset.channels().get(timeline.score().parts().get(i).id());
      // New parts keep their settings; deleted parts are ignored.
      if (channel == null) continue;
      volumes[i] = channel.volume();
      pans[i] = channel.pan();
      muted[i] = channel.mute();
      solo[i] = channel.solo();
    }
    applyMixer();
    applyTrackMute();
  }

  public synchronized void retrySounds() {
    if (synthesizer != null) {
      loadSounds();
      applyTrackMute();
    }
  }

  public synchronized void setSoundBank(
      javax.sound.midi.Soundbank bank, String id, String name, String error) {
    if (!id.equals("system") && !id.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid sound bank");
    updatePosition();
    silence();
    soundBank = bank;
    soundBankId = id;
    soundBankName = name;
    soundBankError = error;
    java.util.Arrays.fill(soundStatuses, "NOT_LOADED");
    java.util.Arrays.fill(soundNames, "");
    if (synthesizer != null) {
      for (int i = 0; i < volumes.length; i++) sequencer.setTrackMute(i + 1, true);
      loadSounds();
      for (int i = 0; i < volumes.length; i++) {
        var profile = profiles.get(timeline.score().parts().get(i).id());
        var channel = synthesizer.getChannels()[MidiScoreSequence.channel(i)];
        if (channel != null) channel.programChange(0, profile == null ? 0 : profile.midiProgram());
      }
      applyMixer();
      applyTrackMute();
    }
  }

  public synchronized void copySoundBankTo(MidiScorePlayer next) {
    next.setSoundBank(soundBank, soundBankId, soundBankName, soundBankError);
  }

  private void loadSounds() {
    for (int i = 0; i < volumes.length; i++) {
      var profile = profiles.get(timeline.score().parts().get(i).id());
      int program = profile == null ? 0 : profile.midiProgram();
      soundStatuses[i] = "ERROR";
      soundErrors[i] =
          "Timbre indisponível no sintetizador local. Escolha outro instrumento ou tente"
              + " novamente.";
      if (!soundBankError.isEmpty()) {
        soundErrors[i] = soundBankError;
        continue;
      }
      var bank = soundBank == null ? synthesizer.getDefaultSoundbank() : soundBank;
      var available = bank == null ? synthesizer.getAvailableInstruments() : bank.getInstruments();
      for (var instrument : available) {
        if (instrument.getPatch().getBank() != 0 || instrument.getPatch().getProgram() != program)
          continue;
        try {
          if (synthesizer.loadInstrument(instrument)) {
            soundStatuses[i] = "READY";
            soundErrors[i] = "";
            soundNames[i] = instrument.getName();
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
              soundErrors[i],
              soundNames[i]));
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
        master,
        List.copyOf(mixPresets),
        List.copyOf(markers),
        tempoPractice,
        completedLoops,
        soundBankId,
        soundBankName,
        soundBankError);
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
      if (mixer != null) mixer.close();
      mixer = null;
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
    status = "STOPPED";
    if (sequencer != null) sequencer.close();
    if (mixer != null) mixer.close();
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
