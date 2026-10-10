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
} from '@angular/core';
import type { OpenSheetMusicDisplay } from 'opensheetmusicdisplay';
import { signal } from '@angular/core';
import { ScoreSession } from './score-session';
import { FluteFingering } from '../fingering/flute-fingering';
import type { EventHeadView, EventView } from '../../core/score-contract';
import { WorkspaceLayout } from '../../shared/workspace-layout';

export function eventIndexAtTimestamp(events: EventView[], timestamp: number): number | null {
  let elapsed = 0;
  for (const event of events) {
    const onset =
      event.onsetNumerator !== undefined && event.onsetDenominator
        ? event.onsetNumerator / event.onsetDenominator
        : elapsed;
    if (Math.abs(onset - timestamp) < 0.0001) return event.index;
    elapsed += event.numerator / event.denominator;
  }
  return null;
}

@Component({
  selector: 'app-score-workspace',
  imports: [FluteFingering],
  templateUrl: './score-workspace.html',
  styleUrl: './score-workspace.scss',
})
export class ScoreWorkspace implements AfterViewInit, OnDestroy {
  readonly requestedView = output<'import' | 'practice'>();
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
  readonly darkScore = signal(false);
  readonly selectedProjectId = signal('');
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
  semitones = 2;
  sourceKey = '';
  targetKey = '';
  targetInstrument = 'flute';
  @ViewChild('sheet') private sheet?: ElementRef<HTMLDivElement>;
  @ViewChild('studyNotes') private studyNotes?: ElementRef<HTMLDivElement>;
  private renderer?: OpenSheetMusicDisplay;
  private renderGeneration = 0;
  private cursorTick = -1;
  private animationFrame = 0;
  private playbackAnchor = { tick: 0, time: 0, bpm: 120 };
  private destroyed = false;
  private resizeObserver?: ResizeObserver;
  private resizeFrame = 0;

  constructor() {
    if (this.compactViewport) {
      this.layout.explorer.set(false);
      this.layout.inspector.set(false);
    }
    effect(() => {
      const zoom = this.layout.zoom();
      if (this.renderer) {
        this.renderer.Zoom = zoom / 100;
        this.renderer.render();
      }
    });
    effect(() => {
      const transport = this.session.transport();
      if (!transport) return;
      cancelAnimationFrame(this.animationFrame);
      this.playbackAnchor = {
        tick: transport.tick,
        time: performance.now(),
        bpm: transport.bpm,
      };
      this.positionCursor(transport.tick);
      if (transport.status === 'PLAYING') this.animateCursor();
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
    if (typeof ResizeObserver !== 'undefined' && this.sheet) {
      let width = 0;
      this.resizeObserver = new ResizeObserver(([entry]) => {
        if (!entry || entry.contentRect.width === width) return;
        width = entry.contentRect.width;
        cancelAnimationFrame(this.resizeFrame);
        this.resizeFrame = requestAnimationFrame(() => {
          if (width > 0 && !this.destroyed) this.renderer?.render();
        });
      });
      this.resizeObserver.observe(this.sheet.nativeElement);
    }
    this.sourceKey = this.session.score()?.keyId ?? '';
    void this.loadCurrentMeasure().then(() => {
      if (!this.destroyed) this.selectEvent(0);
    });
    if (this.session.xml()) void this.render(this.session.xml());
  }

  async open(event: Event): Promise<void> {
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return;
    const file = input.files[0];
    input.value = '';
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

  async openSavedProject(): Promise<void> {
    const id = this.selectedProjectId();
    if (!id) return;
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
    if (await this.session.saveProject())
      this.selectedProjectId.set(this.session.projectId() ?? '');
  }

  async deleteSavedProject(): Promise<void> {
    const id = this.selectedProjectId();
    if (!id || !window.confirm('Excluir este projeto salvo deste computador?')) return;
    if (await this.session.deleteProject(id)) this.selectedProjectId.set('');
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
      if (selected.kind === 'NOTE') {
        this.step = selected.step;
        this.alter = selected.alter;
        this.octave = selected.octave;
      }
    }
  }

  selectHead(index: number): void {
    const event = this.selectedEvent();
    if (event?.kind === 'CHORD' && event.heads?.some((head) => head.index === index))
      this.selectedHeadIndex.set(index);
    const head = this.selectedHead();
    if (head) {
      const eventHead = event?.heads?.find((candidate) => candidate.index === index);
      if (eventHead) {
        const [step, octaveText] =
          eventHead.pitch.match(/^([A-G])(?:bb|b|##|#)?(-?\d+)$/)?.slice(1) ?? [];
        if (step && octaveText) {
          this.step = step;
          this.octave = Number(octaveText);
          this.alter =
            eventHead.pitch.includes('bb') || eventHead.pitch.includes('##')
              ? eventHead.pitch.includes('bb')
                ? -2
                : 2
              : eventHead.pitch.includes('b')
                ? -1
                : eventHead.pitch.includes('#')
                  ? 1
                  : 0;
        }
      }
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
    const point = graphic.svgToOsmd(graphic.domToSvg(new PointF2D(event.clientX, event.clientY)));
    const note = graphic.GetNearestNote(point, new PointF2D(2.5, 2.5));
    if (!note) return;
    const position = note.PositionAndShape.AbsolutePosition;
    if (Math.hypot(point.x - position.x, point.y - position.y) > 2.5) return;
    const measure = note.parentVoiceEntry.parentStaffEntry.parentMeasure;
    const measureIndex = graphic.MeasureList.findIndex((row) => row.includes(measure));
    const partIndex = graphic.MeasureList[measureIndex]?.indexOf(measure) ?? -1;
    const part = score.parts[partIndex];
    const timestamp = note.parentVoiceEntry.parentStaffEntry.relInMeasureTimestamp.RealValue;
    if (measureIndex < 0 || !part) return;
    if (part.id !== this.activePart()?.id) await this.selectPart(part.id);
    if (part.id !== this.activePart()?.id) return;
    if (!(await this.session.loadMeasureEvents(part.id, measureIndex))) return;
    const events = this.session.score()?.parts.find((candidate) => candidate.id === part.id)
      ?.measures[measureIndex]?.events;
    const eventIndex = events ? eventIndexAtTimestamp(events, timestamp) : null;
    if (eventIndex === null) return;
    const selected = events?.[eventIndex];
    if (selected?.kind !== 'NOTE' && selected?.kind !== 'CHORD') return;
    await this.selectMeasure(measureIndex);
    this.selectEvent(eventIndex);
    const selectedEvent = this.currentMeasure()?.events[eventIndex];
    const measureStart = score.measures[measureIndex]?.startTick ?? 0;
    if (selectedEvent) {
      const onset =
        selectedEvent.onsetNumerator !== undefined && selectedEvent.onsetDenominator
          ? (selectedEvent.onsetNumerator / selectedEvent.onsetDenominator) * 1920
          : 0;
      await this.session.action('seek', { tick: Math.round(measureStart + onset) });
    }
    this.layout.inspector.set(true);
  }

  focusStudyNotes(): void {
    if (this.studyPanel) this.studyPanel.nativeElement.open = true;
    this.studyNotes?.nativeElement.querySelector('button')?.focus();
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
        drawTitle: false,
        followCursor: true,
        autoResize: false,
      });
      await pending.load(xml);
      if (generation !== this.renderGeneration) {
        pending.clear();
        return;
      }
      pending.Zoom = this.layout.zoom() / 100;
      pending.render();
      this.renderer = pending;
      this.cursorTick = -1;
      this.positionCursor(this.session.transport()?.tick ?? 0);
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
    if (!this.renderer || tick === this.cursorTick) return;
    const cursor = this.renderer.cursor;
    if (tick < this.cursorTick || this.cursorTick < 0) cursor.reset();
    const sourceTime = tick / 1920;
    let steps = 0;
    while (
      !cursor.iterator.EndReached &&
      cursor.iterator.CurrentSourceTimestamp.RealValue < sourceTime &&
      steps++ < 100_000
    ) {
      cursor.next();
    }
    if (cursor.iterator.CurrentSourceTimestamp.RealValue > sourceTime && steps > 0)
      cursor.previous();
    if (!cursor.iterator.EndReached) cursor.show();
    this.cursorTick = tick;
  }

  private animateCursor(): void {
    this.animationFrame = requestAnimationFrame((time) => {
      const transport = this.session.transport();
      if (!transport || transport.status !== 'PLAYING' || this.destroyed) return;
      const elapsed = Math.max(0, time - this.playbackAnchor.time);
      const predictedTick = Math.min(
        transport.totalTicks,
        this.playbackAnchor.tick + Math.floor((elapsed * this.playbackAnchor.bpm * 8) / 1000),
      );
      this.positionCursor(predictedTick);
      this.animateCursor();
    });
  }

  toggleScoreTheme(): void {
    this.darkScore.update((dark) => !dark);
    void this.render(this.session.xml());
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.resizeObserver?.disconnect();
    cancelAnimationFrame(this.resizeFrame);
    cancelAnimationFrame(this.animationFrame);
    ++this.renderGeneration;
    this.renderer?.clear();
  }
}
