import { scoreAppearance } from '../../shared/score-appearance';
import {
  AfterViewInit,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
  output,
  signal,
  ViewChild,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import type { OpenSheetMusicDisplay } from 'opensheetmusicdisplay';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { AudioSettings } from '../../core/audio-settings';
import { AudioPreparation } from '../../shared/audio-preparation';
import { AnalysisSetup, analysisDefaults, analysisSetupErrors } from './analysis-setup';
import { ScoreSession } from '../score/score-session';
import { StudyReport, StudyRequest } from './study-report';
import { WorkspaceLayout } from '../../shared/workspace-layout';

import {
  AnalysisView,
  HistorySummary,
  SavedReport,
  isHistorySummary,
  isSavedReport,
  isReport,
  isStudyContext,
} from './analysis-contract';
@Component({
  selector: 'app-analysis-panel',
  imports: [DecimalPipe, StudyReport, AudioPreparation],
  templateUrl: './analysis-panel.html',
  styleUrl: './analysis-panel.scss',
})
export class AnalysisPanel implements OnInit, AfterViewInit, OnDestroy {
  readonly requestedScore = output<void>();
  private readonly layout = inject(WorkspaceLayout);
  statusLabel(status: string | undefined): string {
    const labels: Record<string, string> = {
      IDLE: 'Aguardando',
      COUNT_IN: 'Preparação',
      RECORDING: 'Escutando',
      DONE: 'Concluído',
      STOPPED: 'Interrompido',
      ERROR: 'Erro de captura',
      INSUFFICIENT_DATA: 'Dados insuficientes',
      SCORED: 'Análise concluída',
      ANALYZED: 'Análise concluída',
    };
    return labels[status ?? 'IDLE'] ?? status ?? 'Aguardando';
  }
  readonly score = inject(ScoreSession);
  readonly history = signal<HistorySummary[]>([]);
  readonly savedReport = signal<SavedReport | null>(null);
  readonly matchingHistory = computed(() => {
    const score = this.score.score();
    return score
      ? this.history().filter(
          (entry) => entry.sha256 === score.sha256 && entry.revision === score.revision,
        )
      : [];
  });
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  private readonly captureOwner = crypto.randomUUID();
  readonly audio = inject(AudioSettings);
  readonly deviceId = this.audio.selectedDevice;
  readonly partId = signal('');
  readonly mode = signal<'ANALYSIS' | 'FOLLOW'>('FOLLOW');
  readonly referenceHz = this.audio.referenceHz;
  private readonly setup = inject(AnalysisSetup);
  readonly alignmentWindowMs = this.setup.alignmentWindowMs;
  readonly timingToleranceMs = this.setup.timingToleranceMs;
  readonly pitchToleranceCents = this.setup.pitchToleranceCents;
  readonly rhythmTolerancePercent = this.setup.rhythmTolerancePercent;
  readonly latencyCompensationMs = this.setup.latencyCompensationMs;
  readonly state = signal<AnalysisView | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly active = computed(() => ['COUNT_IN', 'RECORDING'].includes(this.state()?.status ?? ''));
  readonly setupErrors = computed(() =>
    analysisSetupErrors({
      alignmentWindowMs: this.alignmentWindowMs(),
      timingToleranceMs: this.timingToleranceMs(),
      pitchToleranceCents: this.pitchToleranceCents(),
      rhythmTolerancePercent: this.rhythmTolerancePercent(),
      latencyCompensationMs: this.latencyCompensationMs(),
    }),
  );
  readonly setupMessages = computed(() => Object.values(this.setupErrors()));
  readonly canStart = computed(
    () =>
      !this.active() &&
      !this.busy() &&
      this.audio.ready() &&
      ['FOLLOW', 'ANALYSIS'].includes(this.mode()) &&
      !!this.score.score()?.parts.some((part) => part.id === this.partId()) &&
      !this.setupMessages().length,
  );

  inputNumber(event: Event): number {
    return event.target instanceof HTMLInputElement ? event.target.valueAsNumber : NaN;
  }
  resetAnalysisSettings(): void {
    if (this.active() || this.busy()) return;
    this.alignmentWindowMs.set(analysisDefaults.alignmentWindowMs);
    this.timingToleranceMs.set(analysisDefaults.timingToleranceMs);
    this.pitchToleranceCents.set(analysisDefaults.pitchToleranceCents);
    this.rhythmTolerancePercent.set(analysisDefaults.rhythmTolerancePercent);
    this.latencyCompensationMs.set(analysisDefaults.latencyCompensationMs);
  }
  readonly visibleNotes = computed(() => {
    const notes = this.state()?.expected ?? [];
    const current = this.state()?.follow?.noteIndex ?? 0;
    const start = Math.max(0, Math.min(current - 40, notes.length - 80));
    return notes.slice(start, start + 80);
  });
  readonly canFinish = computed(
    () =>
      this.active() &&
      (this.state()?.mode === 'FOLLOW' ||
        (this.state()?.elapsedMs ?? 0) >= (this.state()?.durationMs ?? Infinity)),
  );

  noteName(midi: number): string {
    const names = ['Dó', 'Dó♯', 'Ré', 'Mi♭', 'Mi', 'Fá', 'Fá♯', 'Sol', 'Lá♭', 'Lá', 'Si♭', 'Si'];
    return `${names[midi % 12]}${Math.floor(midi / 12) - 1}`;
  }

  countdownSeconds(): number {
    return Math.max(0, Math.ceil(((this.state()?.startsAtEpochMs ?? 0) - Date.now()) / 1000));
  }
  private timer: ReturnType<typeof setTimeout> | undefined;
  private disposed = false;
  @ViewChild('followSheet') private followSheet?: ElementRef<HTMLDivElement>;
  @ViewChild('reportDetail') private reportDetail?: ElementRef<HTMLDivElement>;
  private renderer?: OpenSheetMusicDisplay;
  private rendering = false;
  private renderedXml = '';
  private cursorTick = -1;

  constructor() {
    effect(() => {
      const parts = this.score.score()?.parts ?? [];
      if (!this.active() && !this.busy() && !parts.some((part) => part.id === this.partId()))
        this.partId.set(parts[0]?.id ?? '');
    });
    effect(() => {
      const state = this.state();
      const index = state?.follow?.noteIndex ?? -1;
      const tick = state?.expectedTicks?.[index];
      if (state?.mode === 'FOLLOW' && tick !== undefined) this.positionCursor(tick);
    });
  }

  ngOnInit(): void {
    this.partId.set(this.score.score()?.parts[0]?.id ?? '');
    void this.initialize();
  }

  ngAfterViewInit(): void {
    if (this.score.xml()) void this.renderScore(this.score.xml());
  }

  private async renderScore(xml: string): Promise<void> {
    const container = this.followSheet?.nativeElement;
    if (!container || this.disposed || this.rendering || xml === this.renderedXml) return;
    this.rendering = true;
    let pending: OpenSheetMusicDisplay | undefined;
    try {
      const bundle = await import('opensheetmusicdisplay');
      if (this.disposed) return;
      const OpenSheetMusicDisplay = (bundle.default ?? bundle).OpenSheetMusicDisplay;
      this.renderer?.clear();
      container.replaceChildren();
      pending = new OpenSheetMusicDisplay(container, {
        backend: 'svg',
        ...scoreAppearance(),
        drawTitle: false,
        followCursor: true,
        autoResize: true,
      });
      await pending.load(xml);
      if (this.disposed) {
        pending.clear();
        return;
      }
      pending.render();
      this.renderer = pending;
      this.renderedXml = xml;
      this.cursorTick = -1;
      const state = this.state();
      const tick = state?.expectedTicks?.[state.follow?.noteIndex ?? -1];
      if (tick !== undefined) this.positionCursor(tick);
    } catch (failure) {
      pending?.clear();
      this.fail(failure);
    } finally {
      this.rendering = false;
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
    )
      cursor.next();
    if (cursor.iterator.CurrentSourceTimestamp.RealValue > sourceTime && steps > 0)
      cursor.previous();
    if (!cursor.iterator.EndReached) cursor.show();
    this.cursorTick = tick;
  }

  private async initialize(): Promise<void> {
    try {
      await this.audio.refresh();
      if (this.disposed) return;
      const state = await this.request('/api/analysis');
      this.accept(state);
      await this.refreshHistory();
      const currentMode = this.state()?.mode;
      if (currentMode) this.mode.set(currentMode);
      if (this.active()) this.schedule();
    } catch (failure) {
      this.fail(failure);
    }
  }

  async start(): Promise<void> {
    if (!this.canStart()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      this.accept(
        await this.request('/api/analysis/start', {
          partId: this.partId(),
          deviceId: this.deviceId(),
          referenceHz: this.referenceHz(),
          consent: true,
          alignmentWindowMs: this.alignmentWindowMs(),
          timingToleranceMs: this.timingToleranceMs(),
          pitchToleranceCents: this.pitchToleranceCents(),
          rhythmTolerancePercent: this.rhythmTolerancePercent(),
          latencyCompensationMs: this.latencyCompensationMs(),
          mode: this.mode(),
        }),
      );
      this.schedule();
    } catch (failure) {
      this.fail(failure);
    } finally {
      this.busy.set(false);
    }
  }

  async finish(): Promise<void> {
    if (!this.canFinish() || this.busy()) return;
    this.busy.set(true);
    clearTimeout(this.timer);
    try {
      this.accept(await this.request('/api/analysis/finish', {}));
      if (this.state()?.report) {
        try {
          await this.refreshHistory();
        } catch (failure) {
          this.fail(failure);
        }
      }
    } catch (failure) {
      this.fail(failure);
      this.schedule();
    } finally {
      this.busy.set(false);
    }
  }

  async stop(): Promise<void> {
    clearTimeout(this.timer);
    this.busy.set(true);
    try {
      this.accept(await this.request('/api/analysis/stop', {}));
    } catch (failure) {
      this.fail(failure);
    } finally {
      this.busy.set(false);
    }
  }

  private schedule(): void {
    if (this.disposed || !this.active()) return;
    this.timer = setTimeout(async () => {
      try {
        this.accept(await this.request('/api/analysis/observe'));
        if (!this.active() && this.state()?.report) {
          try {
            await this.refreshHistory();
          } catch (failure) {
            this.fail(failure);
          }
        }
        this.schedule();
      } catch (failure) {
        this.fail(failure);
        void this.stop();
      }
    }, 100);
  }

  private accept(value: unknown): void {
    if (
      typeof value !== 'object' ||
      value === null ||
      !('status' in value) ||
      typeof value.status !== 'string' ||
      !('message' in value) ||
      typeof value.message !== 'string'
    )
      throw new Error('Resposta da análise inválida.');
    if (!this.disposed) {
      const incoming = value as AnalysisView;
      if (
        (incoming.report && !isReport(incoming.report)) ||
        (incoming.study && !isStudyContext(incoming.study)) ||
        (incoming.study &&
          incoming.report &&
          incoming.study.notes.length !== incoming.report.expectedCount)
      )
        throw new Error('Resultado da análise inválido.');
      const previous = this.state();
      const sameScore =
        previous?.sha256 === incoming.sha256 &&
        previous?.revision === incoming.revision &&
        previous?.partId === incoming.partId;
      this.state.set({
        ...incoming,
        expected: incoming.expected ?? (sameScore ? (previous?.expected ?? null) : null),
        expectedTicks:
          incoming.expectedTicks ?? (sameScore ? (previous?.expectedTicks ?? null) : null),
        study: incoming.study ?? (sameScore ? (previous?.study ?? null) : null),
      });
      if (
        this.state()?.mode === 'FOLLOW' &&
        this.renderedXml !== this.score.xml() &&
        !this.rendering
      )
        setTimeout(() => {
          if (!this.disposed) void this.renderScore(this.score.xml());
        }, 0);
    }
  }

  private async request(
    path: string,
    body?: object,
    method: 'GET' | 'POST' | 'DELETE' = body ? 'POST' : 'GET',
  ): Promise<unknown> {
    if (!this.token) throw new Error('Abra esta janela pelo aplicativo ARPEON.');
    const response = await this.fetch(path, {
      method,
      headers: {
        'X-Arpeon-Session': this.token,
        'X-Arpeon-Capture-Owner': this.captureOwner,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(5000),
      cache: 'no-store',
      credentials: 'omit',
    });
    if (!response.ok) {
      const value: unknown = await response.json().catch(() => null);
      const message =
        typeof value === 'object' &&
        value !== null &&
        'error' in value &&
        typeof value.error === 'string'
          ? value.error
          : 'Falha na análise local.';
      throw new Error(message);
    }
    return response.json() as Promise<unknown>;
  }

  private async refreshHistory(): Promise<void> {
    const value = await this.request('/api/analysis/history');
    if (!Array.isArray(value) || !value.every(isHistorySummary))
      throw new Error('Histórico de análises inválido.');
    if (!this.disposed) this.history.set(value);
  }

  async showSavedReport(id: string): Promise<void> {
    try {
      const value = await this.request(`/api/analysis/history/${encodeURIComponent(id)}`);
      if (!isSavedReport(value)) throw new Error('Relatório salvo inválido.');
      this.savedReport.set(value);
      setTimeout(() => {
        if (!this.disposed) this.reportDetail?.nativeElement.scrollIntoView({ block: 'start' });
      }, 0);
    } catch (failure) {
      this.fail(failure);
    }
  }

  async prepareStudy(request: StudyRequest, saved = false): Promise<void> {
    const source = saved ? this.savedReport() : this.state();
    if (
      this.busy() ||
      this.active() ||
      !source?.study ||
      !source.sha256 ||
      source.revision === null ||
      !source.partId
    )
      return;
    this.busy.set(true);
    this.error.set('');
    try {
      const prepared = await this.score.prepareStudy({
        sha256: source.sha256,
        revision: source.revision,
        partId: source.partId,
        soundingSemitones: source.study.soundingSemitones,
        scoreXmlSha256: source.study.scoreXmlSha256,
        ...request,
      });
      if (!prepared) {
        this.error.set(this.score.error());
        return;
      }
      this.layout.setMode('study');
      this.requestedScore.emit();
    } finally {
      this.busy.set(false);
    }
  }

  compatible(source: AnalysisView | SavedReport | null): boolean {
    const score = this.score.score();
    const transport = this.score.transport();
    const part = transport?.parts.find((p) => p.id === source?.partId);
    const instrument = score?.instruments.find((i) => i.id === part?.instrumentId);
    return (
      !!source?.study &&
      !!score &&
      source.sha256 === score.sha256 &&
      source.revision === score.revision &&
      !!part &&
      instrument?.soundingSemitones === source.study.soundingSemitones
    );
  }

  async deleteSavedReport(id: string): Promise<void> {
    try {
      await this.request(`/api/analysis/history/${encodeURIComponent(id)}`, undefined, 'DELETE');
      if (this.savedReport()?.id === id) this.savedReport.set(null);
      await this.refreshHistory();
    } catch (failure) {
      this.fail(failure);
    }
  }

  private fail(failure: unknown): void {
    if (!this.disposed)
      this.error.set(failure instanceof Error ? failure.message : 'Falha na análise.');
  }

  ngOnDestroy(): void {
    this.disposed = true;
    clearTimeout(this.timer);
    this.renderer?.clear();
    if (this.active()) void this.request('/api/analysis/stop', {}).catch(() => {});
  }
}
