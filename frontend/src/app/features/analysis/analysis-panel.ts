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
  signal,
  ViewChild,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import type { OpenSheetMusicDisplay } from 'opensheetmusicdisplay';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { isDevices, AudioInputDevice } from '../../core/tuner-contract';
import { ScoreSession } from '../score/score-session';

interface Match {
  expectedIndex: number;
  observedIndex: number;
  expectedMidi: number;
  observedMidi: number;
  onsetErrorMs: number;
  durationErrorPercent: number;
  centsError: number;
  confidence: number;
  pitchWithinTolerance: boolean;
  timingWithinTolerance: boolean;
  durationWithinTolerance: boolean;
}
interface Report {
  status: string;
  expectedCount: number;
  observedCount: number;
  matchedCount: number;
  missedCount: number | null;
  extraCount: number | null;
  earlyCount: number | null;
  lateCount: number | null;
  pitchAccuracyPercent: number | null;
  timingAccuracyPercent: number | null;
  durationAccuracyPercent: number | null;
  interOnsetAccuracyPercent: number | null;
  meanAbsoluteTimingErrorMs: number | null;
  meanAbsoluteIntonationCents: number | null;
  meanMatchedConfidence: number | null;
  matches: Match[];
  missedIndices: number[];
  extraIndices: number[];
}
interface HistorySummary {
  id: string;
  projectId: string | null;
  sha256: string;
  revision: number;
  title: string;
  filename: string;
  partName: string;
  mode: string;
  completedAt: string;
  reportStatus: string;
  expectedCount: number;
  matchedCount: number;
  missedCount: number | null;
  extraCount: number | null;
  pitchAccuracyPercent: number | null;
  timingAccuracyPercent: number | null;
  durationAccuracyPercent: number | null;
  interOnsetAccuracyPercent: number | null;
}
interface SavedReport extends HistorySummary {
  partId: string;
  report: Report;
}
interface AnalysisView {
  status: string;
  mode: 'ANALYSIS' | 'FOLLOW';
  title: string | null;
  filename: string | null;
  sha256: string | null;
  revision: number | null;
  partId: string | null;
  partName: string | null;
  soundingSemitones: number | null;
  expectedCount: number | null;
  durationMs: number | null;
  initialBpm: number | null;
  startsAtEpochMs: number | null;
  elapsedMs: number | null;
  observedOnsets: number;
  expected: { index: number; midi: number; onsetMs: number; durationMs: number }[] | null;
  expectedTicks: number[] | null;
  follow: {
    noteIndex: number;
    state: string;
    confidence: number;
    confirmedNotes: number;
    tempoRatio: number;
    tempoSamples: number;
    tempoState: string;
  } | null;
  report: Report | null;
  message: string;
}

@Component({
  selector: 'app-analysis-panel',
  imports: [DecimalPipe],
  templateUrl: './analysis-panel.html',
  styleUrl: './analysis-panel.scss',
})
export class AnalysisPanel implements OnInit, AfterViewInit, OnDestroy {
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
  readonly devices = signal<AudioInputDevice[]>([]);
  readonly deviceId = signal('');
  readonly partId = signal('');
  readonly mode = signal<'ANALYSIS' | 'FOLLOW'>('FOLLOW');
  readonly referenceHz = signal(440);
  readonly alignmentWindowMs = signal(450);
  readonly timingToleranceMs = signal(120);
  readonly pitchToleranceCents = signal(50);
  readonly rhythmTolerancePercent = signal(20);
  readonly latencyCompensationMs = signal(0);
  readonly state = signal<AnalysisView | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly active = computed(() => ['COUNT_IN', 'RECORDING'].includes(this.state()?.status ?? ''));
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
  private renderer?: OpenSheetMusicDisplay;
  private rendering = false;
  private renderedXml = '';
  private cursorTick = -1;

  constructor() {
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
      const devices = await this.request('/api/devices');
      if (!isDevices(devices)) throw new Error('Lista de entradas inválida.');
      if (this.disposed) return;
      this.devices.set(devices);
      this.deviceId.set(devices[0]?.id ?? '');
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
    if (!this.score.score() || !this.partId() || !this.deviceId() || this.busy()) return;
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
    } catch (failure) {
      this.fail(failure);
    }
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

function isHistorySummary(value: unknown): value is HistorySummary {
  return (
    isRecord(value) &&
    'id' in value &&
    typeof value['id'] === 'string' &&
    'projectId' in value &&
    (value['projectId'] === null || typeof value['projectId'] === 'string') &&
    'sha256' in value &&
    typeof value['sha256'] === 'string' &&
    'revision' in value &&
    typeof value['revision'] === 'number' &&
    typeof value['title'] === 'string' &&
    typeof value['filename'] === 'string' &&
    typeof value['partName'] === 'string' &&
    typeof value['mode'] === 'string' &&
    typeof value['completedAt'] === 'string' &&
    'reportStatus' in value &&
    typeof value['reportStatus'] === 'string' &&
    typeof value['expectedCount'] === 'number' &&
    typeof value['matchedCount'] === 'number' &&
    nullableNumber(value['missedCount']) &&
    nullableNumber(value['extraCount']) &&
    nullableNumber(value['pitchAccuracyPercent']) &&
    nullableNumber(value['timingAccuracyPercent']) &&
    nullableNumber(value['durationAccuracyPercent']) &&
    nullableNumber(value['interOnsetAccuracyPercent'])
  );
}

function isSavedReport(value: unknown): value is SavedReport {
  return (
    isRecord(value) &&
    typeof value['id'] === 'string' &&
    (value['projectId'] === null || typeof value['projectId'] === 'string') &&
    typeof value['sha256'] === 'string' &&
    typeof value['revision'] === 'number' &&
    typeof value['title'] === 'string' &&
    typeof value['filename'] === 'string' &&
    typeof value['partId'] === 'string' &&
    typeof value['partName'] === 'string' &&
    typeof value['mode'] === 'string' &&
    typeof value['completedAt'] === 'string' &&
    isReport(value['report'])
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isReport(value: unknown): value is Report {
  return (
    isRecord(value) &&
    typeof value['status'] === 'string' &&
    typeof value['expectedCount'] === 'number' &&
    typeof value['observedCount'] === 'number' &&
    typeof value['matchedCount'] === 'number' &&
    nullableNumber(value['missedCount']) &&
    nullableNumber(value['extraCount']) &&
    nullableNumber(value['earlyCount']) &&
    nullableNumber(value['lateCount']) &&
    nullableNumber(value['pitchAccuracyPercent']) &&
    nullableNumber(value['timingAccuracyPercent']) &&
    nullableNumber(value['durationAccuracyPercent']) &&
    nullableNumber(value['interOnsetAccuracyPercent']) &&
    nullableNumber(value['meanAbsoluteTimingErrorMs']) &&
    nullableNumber(value['meanAbsoluteIntonationCents']) &&
    nullableNumber(value['meanMatchedConfidence']) &&
    Array.isArray(value['matches']) &&
    value['matches'].every(isMatch) &&
    Array.isArray(value['missedIndices']) &&
    value['missedIndices'].every(Number.isInteger) &&
    Array.isArray(value['extraIndices']) &&
    value['extraIndices'].every(Number.isInteger)
  );
}

function nullableNumber(value: unknown): value is number | null {
  return value === null || (typeof value === 'number' && Number.isFinite(value));
}

function isMatch(value: unknown): value is Match {
  return (
    isRecord(value) &&
    ['expectedIndex', 'observedIndex', 'expectedMidi', 'observedMidi'].every((field) =>
      Number.isInteger(value[field]),
    ) &&
    ['onsetErrorMs', 'durationErrorPercent', 'centsError', 'confidence'].every(
      (field) => typeof value[field] === 'number' && Number.isFinite(value[field]),
    ) &&
    ['pitchWithinTolerance', 'timingWithinTolerance', 'durationWithinTolerance'].every(
      (field) => typeof value[field] === 'boolean',
    )
  );
}
