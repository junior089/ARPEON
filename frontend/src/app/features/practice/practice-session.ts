import { inject, Injectable, OnDestroy, signal } from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import {
  isPractice,
  isPracticeHistory,
  isPracticeObservation,
  PracticeSummary,
  PracticeView,
} from './practice-contract';

export interface PracticeOptions {
  kind: string;
  keyId: string;
  octave: number;
  scaleType: string;
  bpm: number;
  loop: boolean;
  progressiveStep: number;
}

@Injectable()
export class PracticeSession implements OnDestroy {
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly view = signal<PracticeView | null>(null);
  readonly history = signal<PracticeSummary[]>([]);
  readonly busy = signal(false);
  readonly error = signal('');
  private timer: ReturnType<typeof setTimeout> | undefined;
  private disposed = false;
  private generation = 0;

  async load(): Promise<PracticeView | null> {
    try {
      const saved = await this.request('/api/practice/history');
      if (!isPracticeHistory(saved)) throw new Error('Histórico local inválido.');
      if (!this.disposed) this.history.set(saved);
      const current = await this.request('/api/practice', undefined, true);
      if (current !== null && !isPractice(current)) throw new Error('Exercício local inválido.');
      if (current && !this.disposed) {
        this.view.set(current);
        this.schedule();
      }
      return current;
    } catch (failure: unknown) {
      this.showError(failure);
      return null;
    }
  }

  async generate(options: PracticeOptions): Promise<boolean> {
    const value = await this.change('/api/practice/create', options);
    return value !== null;
  }

  async start(): Promise<void> {
    await this.change('/api/practice/start', {});
  }

  async tap(): Promise<void> {
    await this.change('/api/practice/tap', {});
  }

  async playback(action: 'play' | 'pause' | 'stop'): Promise<void> {
    await this.change(`/api/practice/playback/${action}`, {});
  }

  async finish(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const value = await this.request('/api/practice/finish', {});
      if (
        typeof value !== 'object' ||
        value === null ||
        !('history' in value) ||
        !isPracticeHistory(value.history)
      )
        throw new Error('Histórico local inválido.');
      if (this.disposed) return;
      this.history.set(value.history);
      await this.refresh();
    } catch (failure: unknown) {
      this.showError(failure);
    } finally {
      this.busy.set(false);
    }
  }

  private async change(path: string, body: object): Promise<PracticeView | null> {
    if (this.busy()) return null;
    this.busy.set(true);
    this.error.set('');
    try {
      const value = await this.request(path, body);
      if (!isPractice(value)) throw new Error('Resposta do exercício inválida.');
      if (this.disposed) return null;
      this.view.set(value);
      this.schedule();
      return value;
    } catch (failure: unknown) {
      this.showError(failure);
      return null;
    } finally {
      this.busy.set(false);
    }
  }

  private schedule(): void {
    clearTimeout(this.timer);
    const generation = ++this.generation;
    this.timer = setTimeout(() => void this.poll(generation), 200);
  }

  private async poll(generation: number): Promise<void> {
    try {
      const result = await this.request('/api/practice/observe');
      if (this.disposed || generation !== this.generation) return;
      if (!isPracticeObservation(result)) throw new Error('Leitura de prática inválida.');
      this.view.set(result.practice);
      this.timer = setTimeout(() => void this.poll(generation), 200);
    } catch (failure: unknown) {
      if (this.disposed || generation !== this.generation) return;
      this.showError(failure);
    }
  }

  private async refresh(): Promise<void> {
    const value = await this.request('/api/practice');
    if (isPractice(value) && !this.disposed) this.view.set(value);
  }

  private async request(path: string, body?: object, allowMissing = false): Promise<unknown> {
    if (!this.token) throw new Error('Abra esta janela pelo aplicativo ARPEON.');
    const response = await this.fetch(path, {
      method: body ? 'POST' : 'GET',
      headers: {
        'X-Arpeon-Session': this.token,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(5000),
      cache: 'no-store',
      credentials: 'omit',
    });
    if (response.status === 409 && allowMissing) return null;
    if (!response.ok) {
      const payload: unknown = await response.json();
      const message =
        typeof payload === 'object' && payload !== null && 'error' in payload
          ? String(payload.error)
          : 'Operação indisponível.';
      throw new Error(message);
    }
    return response.json() as Promise<unknown>;
  }

  private showError(failure: unknown): void {
    if (!this.disposed) this.error.set(failure instanceof Error ? failure.message : 'Falha local.');
  }

  ngOnDestroy(): void {
    this.disposed = true;
    ++this.generation;
    clearTimeout(this.timer);
  }
}
