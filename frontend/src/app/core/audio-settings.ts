import { computed, inject, Injectable, OnDestroy, signal } from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from './local-session';
import { AudioInputDevice, isDevices } from './tuner-contract';

export interface AudioPreferences {
  schemaVersion: 1;
  deviceId: string;
  deviceName: string;
  referenceHz: number;
}
export function validReference(hz: number): boolean {
  return Number.isFinite(hz) && hz >= 400 && hz <= 480;
}
export function isAudioPreferences(value: unknown): value is AudioPreferences {
  if (!value || typeof value !== 'object') return false;
  const p = value as Record<string, unknown>;
  return (
    Object.keys(p).sort().join(',') === 'deviceId,deviceName,referenceHz,schemaVersion' &&
    p['schemaVersion'] === 1 &&
    typeof p['deviceId'] === 'string' &&
    p['deviceId'].length <= 128 &&
    typeof p['deviceName'] === 'string' &&
    p['deviceName'].length <= 256 &&
    Array.from(p['deviceId'] + p['deviceName']).every((character) => {
      const code = character.codePointAt(0) ?? 0;
      return code >= 32 && (code < 127 || code > 159);
    }) &&
    typeof p['referenceHz'] === 'number' &&
    validReference(p['referenceHz'])
  );
}

@Injectable({ providedIn: 'root' })
export class AudioSettings implements OnDestroy {
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly devices = signal<AudioInputDevice[]>([]);
  readonly selectedDevice = signal('');
  readonly deviceName = signal('');
  readonly referenceHz = signal(440);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly saved = signal(false);
  readonly deviceError = signal('');
  readonly preferencesError = signal('');
  readonly referencePlaying = signal(false);
  readonly missingDevice = computed(
    () =>
      !!this.selectedDevice() &&
      !this.loading() &&
      !this.devices().some((d) => d.id === this.selectedDevice()),
  );
  readonly ready = computed(
    () =>
      !this.loading() &&
      !this.referencePlaying() &&
      validReference(this.referenceHz()) &&
      this.devices().some((d) => d.id === this.selectedDevice()),
  );
  private loaded?: Promise<void>;
  private refreshing?: Promise<void>;
  private writing?: Promise<void>;
  private pending?: AudioPreferences;
  private timer?: ReturnType<typeof setTimeout>;
  private disposed = false;
  private edits = 0;

  refresh(): Promise<void> {
    if (this.refreshing) return this.refreshing;
    this.refreshing = this.discover().finally(() => {
      this.refreshing = undefined;
    });
    return this.refreshing;
  }
  private async discover(): Promise<void> {
    this.loading.set(true);
    this.deviceError.set('');
    try {
      this.loaded ??= this.restore();
      await this.loaded;
      const value = await this.request('/api/devices');
      if (!isDevices(value) || new Set(value.map((d) => d.id)).size !== value.length)
        throw new Error('Lista de entradas inválida. Reinicie o ARPEON.');
      if (this.disposed) return;
      this.devices.set(value);
      if (!this.selectedDevice()) {
        this.selectedDevice.set(value[0]?.id ?? '');
        this.deviceName.set(value[0]?.name ?? '');
      }
    } catch (failure) {
      if (!this.disposed) {
        this.devices.set([]);
        this.deviceError.set(this.message(failure));
      }
    } finally {
      if (!this.disposed) this.loading.set(false);
    }
  }
  private async restore(): Promise<void> {
    const edits = this.edits;
    try {
      const value = await this.request('/api/audio/preferences');
      if (value !== null && !isAudioPreferences(value))
        throw new Error(
          'Ajustes de áudio inválidos. Escolha a entrada e guarde os ajustes novamente.',
        );
      if (this.disposed || edits !== this.edits || value === null) return;
      this.selectedDevice.set(value.deviceId);
      this.deviceName.set(value.deviceName);
      this.referenceHz.set(value.referenceHz);
      this.saved.set(true);
    } catch (failure) {
      if (!this.disposed) this.preferencesError.set(this.message(failure));
    }
  }
  selectDevice(id: string): void {
    const device = this.devices().find((d) => d.id === id);
    if (!device) return;
    this.selectedDevice.set(device.id);
    this.deviceName.set(device.name);
    this.changed();
  }
  setReference(hz: number): void {
    this.referenceHz.set(hz);
    this.changed();
  }
  private changed(): void {
    ++this.edits;
    this.saved.set(false);
    clearTimeout(this.timer);
    // An invalid draft never replaces the last good reference on disk.
    const snapshot = this.snapshot();
    if (!snapshot) return;
    this.pending = snapshot;
    this.timer = setTimeout(() => {
      void this.flush();
    }, 300);
  }
  private snapshot(): AudioPreferences | undefined {
    if (!validReference(this.referenceHz()) || this.missingDevice()) return undefined;
    return {
      schemaVersion: 1,
      deviceId: this.selectedDevice(),
      deviceName: this.deviceName(),
      referenceHz: this.referenceHz(),
    };
  }
  async retrySave(): Promise<void> {
    this.pending = this.snapshot();
    await this.flush();
  }
  flush(): Promise<void> {
    clearTimeout(this.timer);
    if (this.writing) return this.writing;
    this.writing = this.writePending().finally(() => {
      this.writing = undefined;
    });
    return this.writing;
  }
  private async writePending(): Promise<void> {
    this.saving.set(true);
    try {
      while (this.pending && !this.disposed) {
        const snapshot = this.pending;
        this.pending = undefined;
        try {
          const response = await this.request('/api/audio/preferences', snapshot);
          if (
            !isAudioPreferences(response) ||
            response.deviceId !== snapshot.deviceId ||
            response.deviceName !== snapshot.deviceName ||
            response.referenceHz !== snapshot.referenceHz
          )
            throw new Error('Não foi possível confirmar os ajustes guardados. Tente novamente.');
          if (this.disposed) return;
          this.preferencesError.set('');
          this.saved.set(
            !this.pending &&
              validReference(this.referenceHz()) &&
              snapshot.deviceId === this.selectedDevice() &&
              snapshot.referenceHz === this.referenceHz(),
          );
        } catch (failure) {
          if (!this.disposed) {
            this.pending ??= snapshot;
            this.preferencesError.set(this.message(failure));
          }
          return;
        }
      }
    } finally {
      if (!this.disposed) this.saving.set(false);
    }
  }
  private async request(path: string, body?: AudioPreferences): Promise<unknown> {
    if (!this.token)
      throw new Error('Abra esta janela pelo aplicativo ARPEON para acessar o áudio.');
    const response = await this.fetch(path, {
      method: body ? 'PUT' : 'GET',
      headers: {
        'X-Arpeon-Session': this.token,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      cache: 'no-store',
      credentials: 'omit',
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) {
      const failure: unknown = await response.json().catch(() => null);
      throw new Error(
        failure &&
          typeof failure === 'object' &&
          'error' in failure &&
          typeof failure.error === 'string'
          ? failure.error
          : 'Não foi possível acessar os ajustes de áudio. Tente novamente.',
      );
    }
    return response.json() as Promise<unknown>;
  }
  private message(failure: unknown): string {
    return failure instanceof Error &&
      failure.name !== 'TypeError' &&
      failure.name !== 'TimeoutError'
      ? failure.message
      : 'A conexão local falhou. Reinicie o ARPEON ou tente novamente.';
  }
  ngOnDestroy(): void {
    this.disposed = true;
    clearTimeout(this.timer);
  }
}
