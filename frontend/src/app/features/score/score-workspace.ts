import {
  AfterViewInit,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import type { OpenSheetMusicDisplay } from 'opensheetmusicdisplay';
import { signal } from '@angular/core';
import { ScoreSession } from './score-session';

@Component({
  selector: 'app-score-workspace',
  templateUrl: './score-workspace.html',
  styleUrl: './score-workspace.scss',
})
export class ScoreWorkspace implements AfterViewInit, OnDestroy {
  readonly session = inject(ScoreSession);
  readonly measureIndex = signal(0);
  readonly eventIndex = signal(0);
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
  private renderer?: OpenSheetMusicDisplay;
  private renderGeneration = 0;
  private cursorTick = -1;
  private destroyed = false;

  constructor() {
    effect(() => {
      const tick = this.session.transport()?.tick;
      if (tick !== undefined && this.renderer) this.positionCursor(tick);
    });
  }

  ngAfterViewInit(): void {
    if (this.session.xml()) void this.render(this.session.xml());
  }

  async open(event: Event): Promise<void> {
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return;
    const file = input.files[0];
    input.value = '';
    if ((await this.session.open(file)) && !this.destroyed) {
      this.measureIndex.set(0);
      this.selectEvent(0);
      this.sourceKey = this.session.score()?.keyId ?? '';
      this.targetKey = '';
      await this.render(this.session.xml());
    }
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

  selectMeasure(index: number): void {
    this.measureIndex.set(index);
    this.selectEvent(0);
  }

  async selectPart(id: string): Promise<void> {
    await this.session.action('select-part', { id });
    if (this.activePart()?.id === id) {
      this.measureIndex.set(0);
      this.selectEvent(0);
    }
  }

  selectEvent(index: number): void {
    this.eventIndex.set(index);
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

  private selection(): { partId: string; measureIndex: number; eventIndex: number } | null {
    const partId = this.activePart()?.id;
    return partId && this.selectedEvent()
      ? { partId, measureIndex: this.measureIndex(), eventIndex: this.eventIndex() }
      : null;
  }

  private pitch(): { step: string; alter: number; octave: number } {
    return { step: this.step, alter: this.alter, octave: this.octave };
  }

  private duration(): { numerator: number; denominator: number } {
    return { numerator: this.numerator, denominator: this.denominator };
  }

  async editSelected(
    action: 'set-pitch' | 'set-duration' | 'insert-note' | 'insert-rest' | 'delete',
  ) {
    const selected = this.selection();
    if (!selected) return;
    let params: Record<string, string | number> = { ...selected };
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
      this.selectEvent(
        Math.min(this.eventIndex(), (this.currentMeasure()?.events.length ?? 1) - 1),
      );
    }
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
        darkMode: true,
        drawTitle: false,
        followCursor: true,
        autoResize: true,
      });
      await pending.load(xml);
      if (generation !== this.renderGeneration) {
        pending.clear();
        return;
      }
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

  ngOnDestroy(): void {
    this.destroyed = true;
    ++this.renderGeneration;
    this.renderer?.clear();
  }
}
