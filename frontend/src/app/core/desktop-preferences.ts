import { inject, Injectable, OnDestroy, signal } from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from './local-session';

export type WorkspaceMode = 'custom' | 'play' | 'edit' | 'study';
export interface UiPreferences {
  schemaVersion: 1;
  theme: 'dark' | 'light';
  explorer: boolean;
  inspector: boolean;
  inspectorWidth: number;
  zoom: number;
  mode: WorkspaceMode;
  scoreDark: boolean;
  readingEnabled?: boolean;
  readingLanguage?: 'letters' | 'portuguese';
  readingOctave?: boolean;
  readingScope?: 'all' | 'selection';
  followFingering?: boolean;
}

const defaults: UiPreferences = {
  schemaVersion: 1,
  theme: 'dark',
  explorer: true,
  inspector: true,
  inspectorWidth: 320,
  zoom: 100,
  mode: 'custom',
  scoreDark: false,
};

function isPreferences(value: unknown): value is UiPreferences {
  if (typeof value !== 'object' || value === null) return false;
  const data = value as Record<string, unknown>;
  return (
    data['schemaVersion'] === 1 &&
    ['dark', 'light'].includes(String(data['theme'])) &&
    ['custom', 'play', 'edit', 'study'].includes(String(data['mode'])) &&
    typeof data['explorer'] === 'boolean' &&
    typeof data['inspector'] === 'boolean' &&
    typeof data['scoreDark'] === 'boolean' &&
    (data['readingEnabled'] === undefined || typeof data['readingEnabled'] === 'boolean') &&
    (data['readingOctave'] === undefined || typeof data['readingOctave'] === 'boolean') &&
    (data['followFingering'] === undefined || typeof data['followFingering'] === 'boolean') &&
    (data['readingLanguage'] === undefined ||
      ['letters', 'portuguese'].includes(String(data['readingLanguage']))) &&
    (data['readingScope'] === undefined ||
      ['all', 'selection'].includes(String(data['readingScope']))) &&
    Number.isInteger(data['inspectorWidth']) &&
    Number(data['inspectorWidth']) >= 280 &&
    Number(data['inspectorWidth']) <= 460 &&
    Number.isInteger(data['zoom']) &&
    Number(data['zoom']) >= 50 &&
    Number(data['zoom']) <= 200
  );
}

@Injectable({ providedIn: 'root' })
export class DesktopPreferences implements OnDestroy {
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  private value = { ...defaults };
  private legacy = false;
  private loaded = false;
  private pending = false;
  private writer?: Promise<void>;
  private timer?: ReturnType<typeof setTimeout>;
  private disposed = false;
  readonly error = signal('');
  readonly saving = signal(false);

  constructor() {
    try {
      const theme = localStorage.getItem('arpeon.theme.v1');
      if (theme === 'light' || theme === 'dark') {
        this.value.theme = theme;
        this.legacy = true;
      }
      const raw = localStorage.getItem('arpeon.layout.v1');
      const layout: unknown = JSON.parse(raw ?? 'null');
      if (typeof layout !== 'object' || layout === null) return;
      const data = layout as Record<string, unknown>;
      this.legacy = true;
      for (const key of ['explorer', 'inspector', 'scoreDark'] as const)
        if (typeof data[key] === 'boolean') this.value[key] = data[key];
      for (const key of ['readingEnabled', 'readingOctave', 'followFingering'] as const)
        if (typeof data[key] === 'boolean') this.value[key] = data[key];
      if (data['readingLanguage'] === 'letters' || data['readingLanguage'] === 'portuguese')
        this.value.readingLanguage = data['readingLanguage'];
      if (data['readingScope'] === 'all' || data['readingScope'] === 'selection')
        this.value.readingScope = data['readingScope'];
      for (const [key, min, max] of [
        ['inspectorWidth', 280, 460],
        ['zoom', 50, 200],
      ] as const)
        if (typeof data[key] === 'number' && Number.isFinite(data[key]))
          this.value[key] = Math.round(Math.max(min, Math.min(max, data[key])));
      if (['custom', 'play', 'edit', 'study'].includes(String(data['mode'])))
        this.value.mode = data['mode'] as WorkspaceMode;
    } catch {
      // Browser storage is only a migration source and an optional cache.
    }
  }

  snapshot(): UiPreferences {
    return { ...this.value };
  }

  async load(): Promise<void> {
    if (!this.token || this.loaded) return;
    try {
      const response = await this.request('GET');
      const value: unknown = await response.json();
      if (value !== null && !isPreferences(value)) throw new Error('Invalid preferences');
      if (isPreferences(value)) {
        this.value = { ...value };
        this.cache();
      }
      this.loaded = true;
      if (value === null && this.legacy) {
        this.pending = true;
        await this.flush();
      }
    } catch {
      this.loaded = true;
      this.error.set(
        'Não foi possível recuperar as preferências. Os ajustes desta janela continuam disponíveis.',
      );
    }
  }

  update(patch: Partial<Omit<UiPreferences, 'schemaVersion'>>): void {
    const next = { ...this.value, ...patch };
    if (!isPreferences(next)) throw new Error('Invalid preferences');
    if (
      Object.entries(patch).every(
        ([key, value]) => this.value[key as keyof UiPreferences] === value,
      )
    )
      return;
    this.value = next;
    this.cache();
    if (!this.loaded || !this.token || this.disposed) return;
    this.pending = true;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.flush(), 300);
  }

  async retry(): Promise<void> {
    this.pending = true;
    await this.flush();
  }

  flush(): Promise<void> {
    clearTimeout(this.timer);
    if (!this.token || !this.pending || this.disposed) return this.writer ?? Promise.resolve();
    if (this.writer) return this.writer;
    this.writer = this.write().finally(() => {
      this.writer = undefined;
    });
    return this.writer;
  }

  private async write(): Promise<void> {
    this.saving.set(true);
    try {
      while (this.pending && !this.disposed) {
        this.pending = false;
        const snapshot = this.snapshot();
        const response = await this.request('PUT', JSON.stringify(snapshot));
        const saved: unknown = await response.json();
        if (
          !isPreferences(saved) ||
          Object.entries(snapshot).some(
            ([key, value]) => saved[key as keyof UiPreferences] !== value,
          )
        )
          throw new Error('Preferences were not confirmed');
      }
      this.error.set('');
    } catch {
      this.pending = true;
      this.error.set(
        'Não foi possível guardar os ajustes neste computador. Tente novamente antes de fechar.',
      );
    } finally {
      this.saving.set(false);
    }
  }

  private async request(method: 'GET' | 'PUT', body?: string): Promise<Response> {
    const response = await this.fetch('/api/preferences', {
      method,
      headers: { 'X-Arpeon-Session': this.token, 'Content-Type': 'application/json' },
      body,
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
      credentials: 'omit',
    });
    if (!response.ok) throw new Error('Preferences request failed');
    return response;
  }

  private cache(): void {
    try {
      localStorage.setItem('arpeon.theme.v1', this.value.theme);
      const {
        explorer,
        inspector,
        inspectorWidth,
        zoom,
        mode,
        scoreDark,
        readingEnabled,
        readingLanguage,
        readingOctave,
        readingScope,
        followFingering,
      } = this.value;
      localStorage.setItem(
        'arpeon.layout.v1',
        JSON.stringify({
          explorer,
          inspector,
          inspectorWidth,
          zoom,
          mode,
          scoreDark,
          readingEnabled,
          readingLanguage,
          readingOctave,
          readingScope,
          followFingering,
        }),
      );
    } catch {
      // Durable preferences do not depend on browser storage being available.
    }
  }

  ngOnDestroy(): void {
    this.disposed = true;
    clearTimeout(this.timer);
  }
}
