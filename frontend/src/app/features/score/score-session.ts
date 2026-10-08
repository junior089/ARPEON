import { inject, Injectable, OnDestroy, signal } from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import {
  isTransport,
  isWorkspace,
  PreviewView,
  ScoreView,
  TransportState,
} from '../../core/score-contract';

@Injectable()
export class ScoreSession implements OnDestroy {
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly score = signal<ScoreView | null>(null);
  readonly transport = signal<TransportState | null>(null);
  readonly xml = signal('');
  readonly preview = signal<PreviewView | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  private timer: ReturnType<typeof setTimeout> | undefined;
  private generation = 0;
  private disposed = false;

  async acceptImport(
    token: string,
    fallbackOctave: number,
    equalNoteTicks: number,
  ): Promise<boolean> {
    if (this.busy()) return false;
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const result = await this.request('/api/import/accept', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, fallbackOctave, equalNoteTicks, acknowledge: true }),
      });
      if (!isWorkspace(result)) throw new Error('Resposta da partitura inválida.');
      if (this.disposed || generation !== this.generation) return false;
      this.score.set(result.score);
      this.transport.set(result.transport);
      this.xml.set(result.xml);
      this.preview.set(result.preview);
      return true;
    } catch (failure: unknown) {
      this.showError(failure);
      return false;
    } finally {
      this.busy.set(false);
    }
  }

  async open(file: File): Promise<boolean> {
    if (this.busy()) return false;
    if (!/\.(musicxml|xml)$/i.test(file.name) || file.size > 1_048_576) {
      this.error.set('Selecione um MusicXML não comprimido de até 1 MiB.');
      return false;
    }
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const bytes = await file.arrayBuffer();
      try {
        new TextDecoder('utf-8', { fatal: true }).decode(bytes);
      } catch {
        throw new Error('Esta versão requer MusicXML codificado em UTF-8.');
      }
      const result = await this.request('/api/score/open', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/xml',
          'X-Arpeon-Filename': encodeURIComponent(file.name),
        },
        body: bytes,
      });
      if (!isWorkspace(result)) throw new Error('Resposta da partitura inválida.');
      if (this.disposed || generation !== this.generation) return false;
      this.score.set(result.score);
      this.transport.set(result.transport);
      this.xml.set(result.xml);
      this.preview.set(result.preview);
      return true;
    } catch (failure: unknown) {
      this.showError(failure);
      return false;
    } finally {
      this.busy.set(false);
      if (
        !this.disposed &&
        generation === this.generation &&
        this.transport()?.status === 'PLAYING'
      )
        this.schedulePoll(generation);
    }
  }

  async edit(
    name: string,
    params: Record<string, string | number | boolean> = {},
  ): Promise<boolean> {
    if (!this.score() || this.busy()) return false;
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const result = await this.request(`/api/score/edit/${name}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params),
      });
      if (!isWorkspace(result)) throw new Error('Resposta da edição inválida.');
      if (this.disposed || generation !== this.generation) return false;
      this.score.set(result.score);
      this.transport.set(result.transport);
      this.xml.set(result.xml);
      this.preview.set(result.preview);
      return true;
    } catch (failure: unknown) {
      this.showError(failure);
      return false;
    } finally {
      this.busy.set(false);
      if (
        !this.disposed &&
        generation === this.generation &&
        this.transport()?.status === 'PLAYING'
      )
        this.schedulePoll(generation);
    }
  }

  async export(): Promise<void> {
    if (!this.score() || this.busy() || !this.token) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const response = await this.fetch('/api/score/export', {
        headers: { 'X-Arpeon-Session': this.token },
        signal: AbortSignal.timeout(10_000),
        cache: 'no-store',
        credentials: 'omit',
      });
      if (!response.ok) throw new Error('Não foi possível exportar o MusicXML.');
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'arpeon-editado.musicxml';
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (failure: unknown) {
      this.showError(failure);
    } finally {
      this.busy.set(false);
    }
  }

  async action(
    name: string,
    params: Record<string, string | number | boolean> = {},
  ): Promise<void> {
    if (!this.score() || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const result = await this.request(`/api/transport/${name}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params),
      });
      if (!isTransport(result)) throw new Error('Resposta do transporte inválida.');
      if (this.disposed || generation !== this.generation) return;
      this.transport.set(result);
    } catch (failure: unknown) {
      this.showError(failure);
    } finally {
      this.busy.set(false);
      if (
        !this.disposed &&
        generation === this.generation &&
        this.transport()?.status === 'PLAYING'
      )
        this.schedulePoll(generation);
    }
  }

  private schedulePoll(generation: number): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.poll(generation), 80);
  }

  private async poll(generation: number): Promise<void> {
    try {
      const result = await this.request('/api/transport');
      if (this.disposed || generation !== this.generation) return;
      if (!isTransport(result)) throw new Error('Posição de reprodução inválida.');
      this.transport.set(result);
      if (result.status === 'PLAYING') this.schedulePoll(generation);
    } catch (failure: unknown) {
      if (this.disposed || generation !== this.generation) return;
      this.showError(failure);
      this.transport.set(null);
      // Java stops playback after the poll lease expires.
    }
  }

  private async request(path: string, options: RequestInit = {}): Promise<unknown> {
    if (!this.token) throw new Error('Abra esta janela pelo aplicativo ARPEON.');
    let response: Response;
    try {
      response = await this.fetch(path, {
        ...options,
        headers: { 'X-Arpeon-Session': this.token, ...options.headers },
        signal: AbortSignal.timeout(10_000),
        cache: 'no-store',
        credentials: 'omit',
      });
    } catch {
      throw new Error('A conexão local foi interrompida. Reinicie o ARPEON.');
    }
    const result: unknown = await response.json();
    if (!response.ok) {
      if (response.status === 401 || response.status === 403)
        throw new Error('Sessão expirada. Abra o ARPEON novamente.');
      if (
        typeof result === 'object' &&
        result !== null &&
        'error' in result &&
        typeof result.error === 'string'
      )
        throw new Error(result.error);
      throw new Error('Não foi possível processar a partitura.');
    }
    return result;
  }

  private showError(failure: unknown): void {
    if (!this.disposed)
      this.error.set(failure instanceof Error ? failure.message : 'Falha inesperada.');
  }

  ngOnDestroy(): void {
    this.disposed = true;
    ++this.generation;
    clearTimeout(this.timer);
    if (this.transport()?.status === 'PLAYING')
      void this.request('/api/transport/stop', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      }).catch(() => {
        /* The Java poll lease also stops playback. */
      });
  }
}
