import { computed, inject, Injectable, OnDestroy, signal } from '@angular/core';
import {
  AudioInputDevice,
  isDevices,
  isObservation,
  TunerObservation,
} from '../../core/tuner-contract';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';

export { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';

@Injectable()
export class TunerSession implements OnDestroy {
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly devices = signal<AudioInputDevice[]>([]);
  readonly selectedDevice = signal('');
  readonly referenceHz = signal(440);
  readonly observation = signal<TunerObservation | null>(null);
  readonly active = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly ready = computed(
    () =>
      !this.busy() &&
      this.selectedDevice() !== '' &&
      Number.isFinite(this.referenceHz()) &&
      this.referenceHz() >= 400 &&
      this.referenceHz() <= 480,
  );
  private pollTimer: ReturnType<typeof setTimeout> | undefined;
  private disposed = false;
  private generation = 0;

  async refreshDevices(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      const devices: unknown = await this.request('/api/devices');
      if (!isDevices(devices))
        throw new Error('Resposta de dispositivos inválida. Reinicie o aplicativo.');
      if (this.disposed) return;
      this.devices.set(devices);
      if (!devices.some((d) => d.id === this.selectedDevice()))
        this.selectedDevice.set(devices[0]?.id ?? '');
    } catch (failure: unknown) {
      this.showError(failure);
    } finally {
      this.busy.set(false);
    }
  }

  async start(): Promise<void> {
    if (!this.ready() || this.active()) return;
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    try {
      const value: unknown = await this.request('/api/tuner/start', {
        deviceId: this.selectedDevice(),
        referenceHz: this.referenceHz(),
        consent: true,
      });
      if (!isObservation(value))
        throw new Error('Resposta do afinador inválida. Reinicie o aplicativo.');
      if (this.disposed || generation !== this.generation) {
        await this.stopRemote();
        return;
      }
      this.observation.set(value);
      this.active.set(true);
      await this.poll(generation);
    } catch (failure: unknown) {
      this.active.set(false);
      this.showError(failure);
      await this.stopRemote();
    } finally {
      this.busy.set(false);
    }
  }

  async stop(): Promise<void> {
    ++this.generation;
    clearTimeout(this.pollTimer);
    this.active.set(false);
    this.observation.set(null);
    this.busy.set(true);
    try {
      await this.request('/api/tuner/stop', {});
    } catch (failure: unknown) {
      this.showError(failure);
    } finally {
      this.busy.set(false);
    }
  }

  private async poll(generation: number): Promise<void> {
    try {
      const value: unknown = await this.request('/api/tuner');
      if (this.disposed || generation !== this.generation) return;
      if (!isObservation(value)) throw new Error('Leitura inválida. Pare e reinicie o afinador.');
      this.observation.set(value);
      if (value.state === 'ERROR' || value.state === 'IDLE') {
        this.active.set(false);
        this.error.set(
          value.state === 'ERROR'
            ? 'A captura falhou. Verifique a permissão do microfone, reconecte a entrada e tente novamente.'
            : 'O microfone foi desligado. Inicie novamente para continuar.',
        );
        await this.stopRemote();
        return;
      }
      // UI refresh only; Java timestamps and samples are the timing authority.
      this.pollTimer = setTimeout(() => {
        void this.poll(generation);
      }, 100);
    } catch (failure: unknown) {
      if (generation !== this.generation || this.disposed) return;
      this.active.set(false);
      this.observation.set(null);
      this.showError(failure);
      await this.stopRemote();
    }
  }

  private async request(path: string, body?: object): Promise<unknown> {
    if (!this.token)
      throw new Error('Abra esta janela pelo aplicativo ARPEON para conectar o afinador.');
    let response: Response;
    try {
      response = await this.fetch(path, {
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
    } catch {
      throw new Error(
        'A conexão local foi interrompida. Reinicie o ARPEON. O microfone desliga automaticamente sem conexão.',
      );
    }
    if (!response.ok) {
      if (response.status === 401 || response.status === 403)
        throw new Error('Sessão expirada. Feche esta aba e abra o ARPEON novamente.');
      throw new Error(
        'Não foi possível acessar a entrada. Verifique a permissão do microfone, feche outros aplicativos de áudio e atualize as entradas.',
      );
    }
    return response.json() as Promise<unknown>;
  }
  private showError(failure: unknown): void {
    if (!this.disposed)
      this.error.set(
        failure instanceof Error ? failure.message : 'Falha inesperada. Reinicie o ARPEON.',
      );
  }
  private async stopRemote(): Promise<void> {
    try {
      await this.request('/api/tuner/stop', {});
    } catch {
      /* The Java watchdog releases capture after five seconds without UI polling. */
    }
  }
  ngOnDestroy(): void {
    this.disposed = true;
    ++this.generation;
    clearTimeout(this.pollTimer);
    if (this.active()) void this.stopRemote();
  }
}
