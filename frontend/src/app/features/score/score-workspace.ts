import { scoreAppearance } from '../../shared/score-appearance';
import {
  AfterViewInit,
  Component,
  computed,
  effect,
  ElementRef,
  HostListener,
  inject,
  OnDestroy,
  output,
  ViewChild,
  untracked,
} from '@angular/core';
import type { OpenSheetMusicDisplay } from 'opensheetmusicdisplay';
import { signal } from '@angular/core';
import { ScoreSession } from './score-session';
import { FluteFingering } from '../fingering/flute-fingering';
import type { EventHeadView, EventView, RangeWarning } from '../../core/score-contract';
import { Icon } from '../../shared/icon';
import { WorkspaceLayout } from '../../shared/workspace-layout';
import { PlaybackCursor } from '../../shared/playback-cursor';
import { DesktopPreferences, WorkspaceMode } from '../../core/desktop-preferences';
import { SCORE_FILE_ACCEPT, importRoute } from '../../core/import-routing';
import { NotationPreview } from '../../shared/notation-preview';
import { annotateScore } from './score-annotations';
import {
  parsePitch,
  pitchLabel,
  concertPitch,
  frequency,
  eventSymbols,
  measureSymbols,
} from './music-reading';
import { ReferenceTone } from '../../core/reference-tone';
import { AudioSettings } from '../../core/audio-settings';
import {
  RHYTHMIC_FIGURES,
  figureDuration,
  identifyFigure,
  durationName,
  durationProblem,
} from './rhythm-duration';

export function eventIndexAtTimestamp(
  events: EventView[],
  timestamp: number,
  pitch?: string,
  voiceId?: string,
): number | null {
  let elapsed = 0;
  for (const event of events) {
    const onset =
      event.onsetNumerator !== undefined && event.onsetDenominator
        ? event.onsetNumerator / event.onsetDenominator
        : elapsed;
    if (
      Math.abs(onset - timestamp) < 0.0001 &&
      (!pitch || event.pitch === pitch || event.heads?.some((head) => head.pitch === pitch)) &&
      (!voiceId || (event.voiceId ?? '1') === voiceId)
    )
      return event.index;
    elapsed += event.numerator / event.denominator;
  }
  return null;
}

@Component({
  selector: 'app-score-workspace',
  imports: [FluteFingering, NotationPreview, Icon],
  templateUrl: './score-workspace.html',
  styleUrl: './score-workspace.scss',
})
export class ScoreWorkspace implements AfterViewInit, OnDestroy {
  private readonly preferences = inject(DesktopPreferences);
  private readonly readingSaved = this.preferences.snapshot();
  readonly readingEnabled = signal(this.readingSaved.readingEnabled ?? false);
  readonly readingLanguage = signal<'letters' | 'portuguese'>(
    this.readingSaved.readingLanguage ?? 'letters',
  );
  readonly readingOctave = signal(this.readingSaved.readingOctave ?? false);
  readonly readingScope = signal<'all' | 'selection'>(this.readingSaved.readingScope ?? 'all');
  readonly followFingering = signal(this.readingSaved.followFingering ?? false);
  readonly tone = inject(ReferenceTone);
  readonly audio = inject(AudioSettings);
  readonly pitchLabel = pitchLabel;
  readonly outsideRangeCount = computed(
    () =>
      this.session.score()?.rangeWarnings.filter((item) => item.kind === 'OUTSIDE_RANGE').length ??
      0,
  );
  readonly selectedPitch = computed(() => parsePitch(this.selectedHead()?.pitch ?? null));
  readonly soundingPitch = computed(() => {
    const pitch = this.selectedPitch();
    const instrument = this.session
      .score()
      ?.instruments.find((item) => item.id === this.activeInstrumentId());
    return pitch ? concertPitch(pitch, instrument?.soundingSemitones ?? 0) : null;
  });
  private readonly readingDocument = computed(() =>
    new DOMParser().parseFromString(this.session.xml(), 'application/xml'),
  );
  readonly symbols = computed(() => {
    const event = this.selectedEvent();
    return event
      ? [
          ...eventSymbols(event),
          ...measureSymbols(
            this.readingDocument(),
            this.activePart()?.id ?? '',
            this.measureIndex(),
          ),
        ]
      : [];
  });
  readonly requestedView = output<'import' | 'practice'>();
  readonly requestedFile = output<File>();
  readonly fileAccept = SCORE_FILE_ACCEPT;
  readonly session = inject(ScoreSession);
  readonly layout = inject(WorkspaceLayout);
  @ViewChild('studyPanel') private studyPanel?: ElementRef<HTMLDetailsElement>;
  private resizeStart: { x: number; width: number } | null = null;
  private compactViewport = window.innerWidth <= 980;
  private widePanels = { explorer: this.layout.explorer(), inspector: this.layout.inspector() };

  @HostListener('window:resize')
  adaptPanels(): void {
    const compact = window.innerWidth <= 980;
    if (compact === this.compactViewport) return;
    this.compactViewport = compact;
    if (compact) {
      this.widePanels = { explorer: this.layout.explorer(), inspector: this.layout.inspector() };
      this.layout.explorer.set(false);
      this.layout.inspector.set(false);
    } else {
      this.layout.explorer.set(this.widePanels.explorer);
      this.layout.inspector.set(this.widePanels.inspector);
    }
  }

  setZoom(value: number): void {
    this.layout.setZoom(value);
  }

  startResize(event: PointerEvent): void {
    if (!(event.currentTarget instanceof HTMLElement)) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    this.resizeStart = { x: event.clientX, width: this.layout.inspectorWidth() };
  }

  resize(event: PointerEvent): void {
    if (this.resizeStart)
      this.layout.setWidth(this.resizeStart.width + this.resizeStart.x - event.clientX);
  }

  endResize(event: PointerEvent): void {
    this.resizeStart = null;
    if (
      event.currentTarget instanceof HTMLElement &&
      event.currentTarget.hasPointerCapture(event.pointerId)
    )
      event.currentTarget.releasePointerCapture(event.pointerId);
  }

  resizeKey(event: KeyboardEvent): void {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    this.layout.setWidth(
      event.key === 'Home'
        ? 280
        : event.key === 'End'
          ? 460
          : this.layout.inspectorWidth() + (event.key === 'ArrowLeft' ? 16 : -16),
    );
  }
  readonly measureIndex = signal(0);
  readonly eventIndex = signal(0);
  readonly selectedHeadIndex = signal(0);
  readonly studyPage = signal(0);
  readonly darkScore = this.layout.scoreDark;
  readonly pageCount = signal(1);
  readonly pageNumber = signal(1);
  readonly pageNumbers = computed(() => Array.from({ length: this.pageCount() }, (_, i) => i + 1));
  readonly activePart = computed(() =>
    this.session.score()?.parts.find((part) => part.id === this.session.transport()?.activePartId),
  );
  readonly activeInstrumentId = computed(
    () =>
      this.session.transport()?.parts.find((part) => part.id === this.activePart()?.id)
        ?.instrumentId ?? 'concert',
  );
  readonly currentMeasure = computed(() => this.activePart()?.measures[this.measureIndex()]);
  readonly selectedEvent = computed(() => this.currentMeasure()?.events[this.eventIndex()]);
  readonly selectedHead = computed<EventHeadView | null>(() => {
    const event = this.selectedEvent();
    if (!event || event.kind === 'REST') return null;
    return (
      event.heads?.find((head) => head.index === this.selectedHeadIndex()) ??
      (event.kind === 'NOTE'
        ? {
            index: 0,
            pitch: event.pitch,
            tieStart: event.tieStart,
            tieStop: event.tieStop,
          }
        : null)
    );
  });
  readonly studyPageCount = computed(() =>
    Math.ceil((this.currentMeasure()?.events.length ?? 0) / 48),
  );
  readonly studyPageStart = computed(() => this.studyPage() * 48 + 1);
  readonly studyPageEnd = computed(() =>
    Math.min((this.studyPage() + 1) * 48, this.currentMeasure()?.events.length ?? 0),
  );
  readonly visibleStudyEvents = computed(() => {
    const events = this.currentMeasure()?.events ?? [];
    const start = this.studyPage() * 48;
    return events.slice(start, start + 48);
  });
  step = 'C';
  alter = 0;
  octave = 4;
  numerator = 1;
  denominator = 4;
  readonly rhythmicFigures = RHYTHMIC_FIGURES;
  readonly durationName = durationName;
  fractionMode = false;

  get figureValue(): string {
    return this.fractionMode ? 'custom' : String(identifyFigure(this)?.denominator ?? 'custom');
  }

  get durationDots(): number {
    return identifyFigure(this)?.dots ?? 0;
  }

  chooseFigure(value: string): void {
    this.fractionMode = value === 'custom';
    if (this.fractionMode) return;
    const denominator = Number(value);
    if (!this.rhythmicFigures.some((figure) => figure.denominator === denominator)) return;
    Object.assign(this, figureDuration(denominator, this.durationDots));
  }

  chooseDots(value: number): void {
    const figure = identifyFigure(this);
    if (this.fractionMode || !figure || ![0, 1, 2].includes(value)) return;
    Object.assign(this, figureDuration(figure.denominator, value));
  }

  durationError(inserting = false): string {
    const measure = this.currentMeasure();
    const meter =
      measure?.durationNumerator && measure.durationDenominator
        ? { numerator: measure.durationNumerator, denominator: measure.durationDenominator }
        : null;
    return durationProblem(this, this.selectedEvent(), measure?.events ?? [], meter, inserting);
  }
  semitones = 2;
  sourceKey = '';
  targetKey = '';
  targetInstrument = 'flute';
  @ViewChild('sheet') private sheet?: ElementRef<HTMLDivElement>;
  @ViewChild('documentRegion') private documentRegion?: ElementRef<HTMLDivElement>;
  @ViewChild('studyNotes') private studyNotes?: ElementRef<HTMLDivElement>;
  private renderer?: OpenSheetMusicDisplay;
  private renderGeneration = 0;
  private playbackCursor?: PlaybackCursor;
  private animationFrame = 0;
  private playbackAnchor = { tick: 0, time: 0, bpm: 120 };
  private destroyed = false;
  private resizeObserver?: ResizeObserver;
  private resizeFrame = 0;

  constructor() {
    effect(() => {
      this.readingEnabled();
      this.readingLanguage();
      this.readingOctave();
      this.readingScope();
      if (this.readingScope() === 'selection') {
        this.selectedHead();
        this.selectedEvent();
        this.measureIndex();
        this.activePart();
      }
      untracked(() => this.refreshAnnotations());
      this.preferences.update({
        readingEnabled: this.readingEnabled(),
        readingLanguage: this.readingLanguage(),
        readingOctave: this.readingOctave(),
        readingScope: this.readingScope(),
        followFingering: this.followFingering(),
      });
    });
    const selection = this.session.studySelection?.();
    if (selection) {
      this.measureIndex.set(selection.measureIndex);
      this.eventIndex.set(selection.eventIndex);
      this.studyPage.set(Math.floor(selection.eventIndex / 48));
    }
    this.layout.restorePanels();
    this.widePanels = { explorer: this.layout.explorer(), inspector: this.layout.inspector() };
    if (this.compactViewport) {
      this.layout.explorer.set(false);
      this.layout.inspector.set(false);
    }
    effect(() => {
      const zoom = this.layout.zoom();
      if (this.renderer) {
        this.renderer.Zoom = zoom / 100;
        this.redraw();
      }
    });
    effect(() => {
      const transport = this.session.transport();
      if (!transport) return;
      cancelAnimationFrame(this.animationFrame);
      this.playbackAnchor = {
        tick: transport.audibleTick ?? transport.tick,
        time: performance.now(),
        bpm: transport.audibleBpm ?? transport.bpm,
      };
      this.positionCursor(this.playbackAnchor.tick);
      if (transport.status === 'PLAYING' && transport.countInBeatsLeft === 0) this.animateCursor();
    });
    effect(() => {
      const partId = this.activePart()?.id;
      const scoreRevision = this.session.score()?.revision;
      const measureIndex = this.measureIndex();
      if (partId && scoreRevision !== undefined)
        void this.session.loadMeasureEvents(partId, measureIndex);
    });
    if (this.session.score()) {
      void this.session.refreshProjects();
    } else {
      void this.session.reconnect().then(async (restored) => {
        void this.session.refreshProjects();
        if (!restored || this.destroyed) return;
        this.measureIndex.set(0);
        await this.loadCurrentMeasure();
        this.selectEvent(0);
        this.sourceKey = this.session.score()?.keyId ?? '';
        this.targetKey = '';
        void this.render(this.session.xml());
      });
    }
  }

  ngAfterViewInit(): void {
    const selection = this.session.studySelection?.();
    if (this.studyPanel && this.layout.mode() === 'study')
      this.studyPanel.nativeElement.open = true;
    if (typeof ResizeObserver !== 'undefined' && this.sheet) {
      let width = 0;
      this.resizeObserver = new ResizeObserver(([entry]) => {
        if (!entry || entry.contentRect.width === width) return;
        width = entry.contentRect.width;
        cancelAnimationFrame(this.resizeFrame);
        this.resizeFrame = requestAnimationFrame(() => {
          if (width > 0 && !this.destroyed) this.redraw();
        });
      });
      this.resizeObserver.observe(this.sheet.nativeElement);
    }
    this.sourceKey = this.session.score()?.keyId ?? '';
    void this.loadCurrentMeasure().then(() => {
      if (!this.destroyed) {
        this.selectEvent(selection?.eventIndex ?? 0);
        if (selection) this.session.studySelection.set(null);
      }
    });
    if (this.session.xml()) void this.render(this.session.xml());
  }

  async open(event: Event): Promise<void> {
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return;
    const file = input.files[0];
    input.value = '';
    if (importRoute(file.name) !== 'direct') {
      this.requestedFile.emit(file);
      return;
    }
    await this.openDocument(file);
  }

  async openDocument(file: File): Promise<void> {
    if (
      this.session.projectDirty() &&
      !window.confirm('A partitura atual tem alterações não salvas. Quer substituí-la?')
    )
      return;
    if ((await this.session.open(file)) && !this.destroyed) {
      this.measureIndex.set(0);
      await this.loadCurrentMeasure();
      this.selectEvent(0);
      this.sourceKey = this.session.score()?.keyId ?? '';
      this.targetKey = '';
      await this.render(this.session.xml());
    }
  }

  async openSavedProject(id: string): Promise<void> {
    if (
      this.session.projectDirty() &&
      !window.confirm('A partitura atual tem alterações não salvas. Quer substituí-la?')
    )
      return;
    if (await this.session.openProject(id)) {
      this.measureIndex.set(0);
      await this.loadCurrentMeasure();
      this.selectEvent(0);
      this.sourceKey = this.session.score()?.keyId ?? '';
      this.targetKey = '';
      await this.render(this.session.xml());
    }
  }

  async saveCurrentProject(): Promise<void> {
    await this.session.saveProject();
  }

  textValue(event: Event): string {
    const target = event.target;
    return target instanceof HTMLInputElement || target instanceof HTMLSelectElement
      ? target.value
      : '';
  }

  numberValue(event: Event): number {
    return Number(this.textValue(event));
  }

  async selectMeasure(index: number): Promise<void> {
    const partId = this.activePart()?.id;
    if (!partId || !Number.isSafeInteger(index) || index < 0) return;
    this.measureIndex.set(index);
    this.studyPage.set(0);
    await this.session.loadMeasureEvents(partId, index);
    this.selectEvent(0);
  }

  async selectPart(id: string): Promise<void> {
    await this.session.action('select-part', { id });
    if (this.activePart()?.id === id) {
      this.measureIndex.set(0);
      await this.loadCurrentMeasure();
      this.selectEvent(0);
    }
  }

  selectEvent(index: number): void {
    this.eventIndex.set(index);
    this.selectedHeadIndex.set(0);
    this.studyPage.set(Math.floor(index / 48));
    const selected = this.selectedEvent();
    if (selected) {
      this.numerator = selected.numerator;
      this.denominator = selected.denominator;
      this.fractionMode = identifyFigure(selected) === null;
      if (selected.kind === 'NOTE') {
        this.step = selected.step;
        this.alter = selected.alter;
        this.octave = selected.octave;
      }
    }
  }

  warningPitch(text: string): string {
    const pitch = parsePitch(text);
    return pitch ? pitchLabel(pitch, 'portuguese') : text;
  }

  warningLocation(warning: RangeWarning): string {
    const part = this.session.score()?.parts.find((item) => item.id === warning.partId);
    const measure = part?.measures[warning.measureIndex];
    return `${part?.name ?? warning.partId} · c. ${measure?.number ?? warning.measureIndex + 1} · voz ${warning.voiceId ?? '1'}`;
  }

  async inspectRangeWarning(warning: RangeWarning): Promise<void> {
    if (this.session.busy()) return;
    if (this.activePart()?.id !== warning.partId) await this.selectPart(warning.partId);
    if (this.activePart()?.id !== warning.partId) return;
    if (!(await this.session.loadMeasureEvents(warning.partId, warning.measureIndex))) return;
    this.measureIndex.set(warning.measureIndex);
    const event = this.currentMeasure()?.events.find(
      (item) =>
        (item.voiceId ?? '1') === (warning.voiceId ?? '1') &&
        (item.eventIndex ?? item.index) === warning.eventIndex,
    );
    if (!event) return;
    this.selectEvent(event.index);
    const head = event.heads?.find((item) => item.pitch === warning.pitch);
    if (head) this.selectHead(head.index);
  }

  selectHead(index: number): void {
    const event = this.selectedEvent();
    if (event?.kind === 'CHORD' && event.heads?.some((head) => head.index === index))
      this.selectedHeadIndex.set(index);
    const pitch = parsePitch(this.selectedHead()?.pitch ?? null);
    if (pitch) {
      this.step = pitch.step;
      this.alter = pitch.alter;
      this.octave = pitch.octave;
    }
  }

  previousStudyPage(): void {
    this.studyPage.update((page) => Math.max(0, page - 1));
  }

  nextStudyPage(): void {
    this.studyPage.update((page) => Math.min(this.studyPageCount() - 1, page + 1));
  }

  async selectRenderedNote(event: MouseEvent): Promise<void> {
    const renderer = this.renderer;
    const score = this.session.score();
    const graphic = renderer?.GraphicSheet;
    if (!graphic || !score || this.session.busy() || !(event.target instanceof SVGElement)) return;
    const bundle = await import('opensheetmusicdisplay');
    if (renderer !== this.renderer) return;
    const PointF2D = (bundle.default ?? bundle).PointF2D;
    const svg = event.target.closest('svg');
    const matrix = svg?.getScreenCTM();
    if (!svg || !matrix) return;
    const pageIndex = this.pages().findIndex((page) => page.contains(svg));
    const page = graphic.MusicPages[pageIndex];
    if (!page) return;
    const screenPoint = svg.createSVGPoint();
    screenPoint.x = event.clientX;
    screenPoint.y = event.clientY;
    const localPoint = screenPoint.matrixTransform(matrix.inverse());
    const point = graphic.svgToOsmd(new PointF2D(localPoint.x, localPoint.y));
    const note = graphic.GetNearestNote(point, new PointF2D(2.5, 2.5), page);
    if (!note) return;
    const position = note.PositionAndShape.AbsolutePosition;
    if (Math.hypot(point.x - position.x, point.y - position.y) > 2.5) return;
    const measure = note.parentVoiceEntry.parentStaffEntry.parentMeasure;
    const measureIndex = graphic.MeasureList.findIndex((row) => row.includes(measure));
    const source = note.sourceNote;
    if (!source || source.isRest()) return;
    const part = score.parts.find(
      (part) => part.id === source.ParentVoiceEntry.ParentVoice.Parent.IdString,
    );
    const pitch = source.Pitch.ToStringShort(3);
    const voiceId = String(source.ParentVoiceEntry.ParentVoice.VoiceId);
    const timestamp = note.parentVoiceEntry.parentStaffEntry.relInMeasureTimestamp.RealValue;
    if (measureIndex < 0 || !part) return;
    if (part.id !== this.activePart()?.id) await this.selectPart(part.id);
    if (part.id !== this.activePart()?.id) return;
    if (!(await this.session.loadMeasureEvents(part.id, measureIndex))) return;
    const events = this.session.score()?.parts.find((candidate) => candidate.id === part.id)
      ?.measures[measureIndex]?.events;
    const eventIndex = events ? eventIndexAtTimestamp(events, timestamp, pitch, voiceId) : null;
    if (eventIndex === null) return;
    const selected = events?.[eventIndex];
    if (selected?.kind !== 'NOTE' && selected?.kind !== 'CHORD') return;
    await this.selectMeasure(measureIndex);
    this.selectEvent(eventIndex);
    if (selected?.kind === 'CHORD') {
      const head = selected.heads?.find((head) => head.pitch === pitch);
      if (head) this.selectHead(head.index);
    }
    const selectedEvent = this.currentMeasure()?.events[eventIndex];
    // The transport contains performed occurrences; the editor contains written measures.
    const measureStart =
      score.measures.find((measure) => measure.number === part.measures[measureIndex]?.number)
        ?.startTick ?? 0;
    if (selectedEvent) {
      const onset =
        selectedEvent.onsetNumerator !== undefined && selectedEvent.onsetDenominator
          ? (selectedEvent.onsetNumerator / selectedEvent.onsetDenominator) * 1920
          : 0;
      await this.session.action('seek', { tick: Math.round(measureStart + onset) });
    }
    if (
      this.layout.mode() === 'edit' ||
      this.layout.mode() === 'custom' ||
      this.layout.mode() === 'study'
    )
      this.layout.inspector.set(true);
  }

  focusStudyNotes(): void {
    if (this.layout.mode() === 'play') this.setWorkspaceMode('study');
    if (this.studyPanel) this.studyPanel.nativeElement.open = true;
    if (this.layout.mode() === 'study')
      this.documentRegion?.nativeElement.parentElement
        ?.querySelector<HTMLSelectElement>('.selection-location select')
        ?.focus();
    else this.studyNotes?.nativeElement.querySelector('button')?.focus();
  }

  private selection(): {
    partId: string;
    measureIndex: number;
    voiceId: string;
    eventIndex: number;
  } | null {
    const partId = this.activePart()?.id;
    const event = this.selectedEvent();
    return partId && event
      ? {
          partId,
          measureIndex: this.measureIndex(),
          voiceId: event.voiceId ?? '1',
          eventIndex: event.eventIndex ?? event.index,
        }
      : null;
  }

  private pitch(): { step: string; alter: number; octave: number } {
    return { step: this.step, alter: this.alter, octave: this.octave };
  }

  private duration(): { numerator: number; denominator: number } {
    return { numerator: this.numerator, denominator: this.denominator };
  }

  async editSelected(
    action: 'set-pitch' | 'set-duration' | 'insert-note' | 'insert-rest' | 'delete' | 'remove-tie',
  ) {
    const selected = this.selection();
    if (!selected) return;
    if (action === 'set-duration' || action === 'insert-note' || action === 'insert-rest') {
      const problem = this.durationError(action !== 'set-duration');
      if (problem) {
        this.session.error.set(problem);
        return;
      }
    }
    let params: Record<string, string | number> = { ...selected };
    if (
      this.selectedEvent()?.kind === 'CHORD' &&
      (action === 'set-pitch' || action === 'delete' || action === 'remove-tie')
    )
      params = { ...params, headIndex: this.selectedHeadIndex() };
    if (action === 'set-pitch' || action === 'insert-note') params = { ...params, ...this.pitch() };
    if (action === 'set-duration' || action === 'insert-note' || action === 'insert-rest')
      params = { ...params, ...this.duration() };
    await this.edit(action, params);
  }

  async previewTransform(kind: 'interval' | 'key' | 'instrument' | 'sounding' | 'octave') {
    const partId = this.activePart()?.id;
    if (!partId) return;
    let params: Record<string, string | number> = { kind, partId };
    if (kind === 'interval' || kind === 'octave') params = { ...params, semitones: this.semitones };
    if (kind === 'key')
      params = { ...params, sourceKey: this.sourceKey, targetKey: this.targetKey };
    if (kind === 'instrument') params = { ...params, targetInstrument: this.targetInstrument };
    await this.edit('preview', params);
  }

  async applyPreview(): Promise<void> {
    const token = this.session.preview()?.token;
    if (token) await this.edit('apply-preview', { token });
  }

  async setInstrument(event: Event): Promise<void> {
    const control = event.target;
    if (!(control instanceof HTMLSelectElement)) return;
    const partId = this.activePart()?.id;
    if (partId) await this.edit('set-instrument', { partId, instrumentId: control.value });
    control.value = this.activeInstrumentId();
  }

  async edit(action: string, params: Record<string, string | number> = {}): Promise<void> {
    const before = this.session.xml();
    if (await this.session.edit(action, params)) {
      if (action === 'apply-preview' || action === 'undo' || action === 'redo')
        this.sourceKey = this.session.score()?.keyId ?? '';
      if (this.session.xml() !== before && !this.destroyed) await this.render(this.session.xml());
      await this.loadCurrentMeasure();
      this.selectEvent(
        Math.min(this.eventIndex(), (this.currentMeasure()?.events.length ?? 1) - 1),
      );
    }
  }

  private async loadCurrentMeasure(): Promise<void> {
    const partId = this.activePart()?.id;
    if (partId) await this.session.loadMeasureEvents(partId, this.measureIndex());
  }

  private async render(xml: string): Promise<void> {
    const container = this.sheet?.nativeElement;
    if (!container || this.destroyed) return;
    const generation = ++this.renderGeneration;
    let pending: OpenSheetMusicDisplay | undefined;
    try {
      const bundle = await import('opensheetmusicdisplay');
      const OpenSheetMusicDisplay = (bundle.default ?? bundle).OpenSheetMusicDisplay;
      if (generation !== this.renderGeneration) return;
      this.renderer?.clear();
      this.renderer = undefined;
      container.replaceChildren();
      pending = new OpenSheetMusicDisplay(container, {
        backend: 'svg',
        ...scoreAppearance(this.darkScore()),
        drawTitle: true,
        followCursor: true,
        autoResize: false,
        pageFormat: 'A4_P',
      });
      await pending.load(xml);
      if (generation !== this.renderGeneration) {
        pending.clear();
        return;
      }
      pending.Zoom = this.layout.zoom() / 100;
      pending.render();
      this.renderer = pending;
      this.refreshAnnotations();
      if (this.studyPanel && this.layout.mode() === 'study')
        this.studyPanel.nativeElement.open = true;
      this.playbackCursor = new PlaybackCursor(pending.cursor);
      this.refreshPages();
      const transport = this.session.transport();
      this.positionCursor(transport?.audibleTick ?? transport?.tick ?? 0);
    } catch (failure: unknown) {
      pending?.clear();
      if (this.destroyed || generation !== this.renderGeneration) return;
      console.error('MusicXML rendering failed', failure);
      this.session.error.set(
        'A partitura foi importada, mas o desenho falhou. Tente outro MusicXML.',
      );
    }
  }

  private positionCursor(tick: number): void {
    if ((this.session.transport()?.countInBeatsLeft ?? 0) > 0) {
      this.renderer?.cursor.hide();
      return;
    }
    this.playbackCursor?.position(tick);
    if (this.followFingering() && this.session.transport()?.status === 'PLAYING')
      this.followPlayingNote();
  }

  private followPlayingNote(): void {
    const iterator = this.renderer?.cursor?.Iterator;
    const note = iterator?.CurrentVoiceEntries.find(
      (entry) => entry.ParentVoice.Parent.IdString === this.activePart()?.id,
    )?.Notes.find((note) => !note.isRest());
    if (!note) return;
    const pitch = note.Pitch.ToStringShort(3);
    const measure = iterator?.CurrentMeasureIndex;
    if (measure === undefined || this.measureIndex() !== measure) {
      if (measure !== undefined) void this.selectMeasure(measure);
      return;
    }
    const index = this.currentMeasure()?.events.findIndex(
      (event) =>
        event.pitch === pitch &&
        Math.abs(
          (event.onsetNumerator ?? 0) / (event.onsetDenominator ?? 1) -
            note.ParentVoiceEntry.Timestamp.RealValue,
        ) < 0.0001,
    );
    if (index !== undefined && index >= 0 && index !== this.eventIndex()) this.selectEvent(index);
  }

  private refreshAnnotations(): void {
    if (!this.renderer || !this.sheet) return;
    const event = this.selectedEvent();
    annotateScore(this.renderer, this.sheet.nativeElement, {
      enabled: this.readingEnabled(),
      language: this.readingLanguage(),
      octave: this.readingOctave(),
      scope: this.readingScope(),
      partId: this.activePart()?.id ?? '',
      measure: this.measureIndex(),
      pitch: this.selectedHead()?.pitch ?? null,
      onset: (event?.onsetNumerator ?? 0) / (event?.onsetDenominator ?? 1),
      voiceId: event?.voiceId ?? '1',
      dark: this.darkScore(),
    });
  }

  async hearSelected(): Promise<void> {
    const pitch = this.soundingPitch();
    if (!pitch || this.session.transport()?.status === 'PLAYING' || this.session.busy()) return;
    await this.tone.playPitch(frequency(pitch.midi, this.audio.referenceHz()));
  }

  private redraw(): void {
    const renderer = this.renderer;
    if (!renderer) return;
    renderer.render();
    this.refreshAnnotations();
    this.playbackCursor = new PlaybackCursor(renderer.cursor);
    this.refreshPages();
    const transport = this.session.transport();
    this.positionCursor(transport?.audibleTick ?? transport?.tick ?? 0);
  }

  private pages(): HTMLElement[] {
    return Array.from(this.sheet?.nativeElement.children ?? []).filter(
      (element): element is HTMLElement =>
        element instanceof HTMLElement && !!element.querySelector('svg'),
    );
  }

  private refreshPages(): void {
    this.pageCount.set(Math.max(1, this.pages().length));
    this.updateVisiblePage();
  }

  updateVisiblePage(): void {
    const region = this.documentRegion?.nativeElement;
    if (!region) return;
    const caption = region.querySelector('.page-caption');
    const viewport = region.getBoundingClientRect();
    const top = viewport.top + (caption?.getBoundingClientRect().height ?? 0) + 16;
    let index = -1;
    let mostVisible = 0;
    this.pages().forEach((page, pageIndex) => {
      const bounds = page.getBoundingClientRect();
      const visible = Math.min(viewport.bottom, bounds.bottom) - Math.max(top, bounds.top);
      if (visible > mostVisible) {
        mostVisible = visible;
        index = pageIndex;
      }
    });
    if (index >= 0) this.pageNumber.set(index + 1);
  }

  goToPage(number: number): void {
    const region = this.documentRegion?.nativeElement;
    const page = this.pages()[number - 1];
    if (!region || !page) return;
    const caption = region.querySelector('.page-caption');
    const top =
      region.scrollTop +
      page.getBoundingClientRect().top -
      region.getBoundingClientRect().top -
      (caption?.getBoundingClientRect().height ?? 0) -
      16;
    region.scrollTo({ top: Math.max(0, top), behavior: 'instant' });
    this.pageNumber.set(number);
  }

  private animateCursor(): void {
    this.animationFrame = requestAnimationFrame((time) => {
      const transport = this.session.transport();
      if (!transport || transport.status !== 'PLAYING' || this.destroyed) return;
      const elapsed = Math.min(160, Math.max(0, time - this.playbackAnchor.time));
      const predictedTick = Math.min(
        transport.loop ? transport.loop.endTick - 1 : transport.totalTicks,
        this.playbackAnchor.tick + Math.floor((elapsed * this.playbackAnchor.bpm * 8) / 1000),
      );
      this.positionCursor(predictedTick);
      this.animateCursor();
    });
  }

  toggleScoreTheme(): void {
    this.layout.toggleScoreTheme();
    void this.render(this.session.xml());
  }

  setWorkspaceMode(mode: Exclude<WorkspaceMode, 'custom'>): void {
    this.layout.setMode(mode);
    this.widePanels = { explorer: this.layout.explorer(), inspector: this.layout.inspector() };
    if (this.compactViewport && mode === 'edit') this.layout.explorer.set(false);
    if (this.studyPanel) this.studyPanel.nativeElement.open = mode === 'study';
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.tone.stop();
    this.resizeObserver?.disconnect();
    cancelAnimationFrame(this.resizeFrame);
    cancelAnimationFrame(this.animationFrame);
    ++this.renderGeneration;
    this.renderer?.clear();
  }
}
