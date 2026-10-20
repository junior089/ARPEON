import { computed, inject, Injectable, signal } from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from './local-session';

export interface SourceProvider {
  id: string;
  name: string;
  host: string;
  availabilityNote: string;
  capabilities: {
    search: boolean;
    importByUrl: boolean;
    formats: string[];
    selectParts: boolean;
    connectAccount: boolean;
  };
}
export function isSourceProviders(value: unknown): value is SourceProvider[] {
  if (!Array.isArray(value) || !value.length || value.length > 32) return false;
  const object = (v: unknown): v is Record<string, unknown> =>
    !!v && typeof v === 'object' && !Array.isArray(v);
  const text = (v: unknown, max: number): v is string =>
    typeof v === 'string' && v.length > 0 && v.length <= max;
  return (
    new Set(value.map((v) => (object(v) ? v['id'] : null))).size === value.length &&
    value.every((v) => {
      if (
        !object(v) ||
        !text(v['id'], 64) ||
        !text(v['name'], 100) ||
        !text(v['host'], 200) ||
        !/^[a-z0-9.-]+$/.test(v['host']) ||
        !text(v['availabilityNote'], 1000) ||
        !object(v['capabilities'])
      )
        return false;
      const c = v['capabilities'];
      return (
        ['search', 'importByUrl', 'selectParts', 'connectAccount'].every(
          (key) => typeof c[key] === 'boolean',
        ) &&
        Array.isArray(c['formats']) &&
        c['formats'].length <= 16 &&
        c['formats'].every((f) => text(f, 32)) &&
        new Set(c['formats']).size === c['formats'].length &&
        (!c['importByUrl'] || c['formats'].length > 0)
      );
    })
  );
}
@Injectable({ providedIn: 'root' })
export class SourceCapabilities {
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly providers = signal<SourceProvider[] | null>(null);
  readonly error = signal('');
  readonly loading = signal(false);
  readonly commons = computed(() => this.providers()?.find((p) => p.id === 'wikimedia-commons'));
  readonly canImportCommons = computed(
    () =>
      this.commons()?.capabilities.importByUrl === true &&
      this.commons()?.capabilities.formats.includes('MIDI') === true,
  );
  private pending?: Promise<void>;
  load(force = false): Promise<void> {
    if (this.pending) return this.pending;
    if (this.providers() && !force) return Promise.resolve();
    this.pending = this.read().finally(() => {
      this.pending = undefined;
    });
    return this.pending;
  }
  private async read(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      if (!this.token) throw new Error('Abra esta janela pelo aplicativo ARPEON.');
      const response = await this.fetch('/api/import/providers', {
        headers: { 'X-Arpeon-Session': this.token },
        signal: AbortSignal.timeout(10000),
        credentials: 'omit',
        cache: 'no-store',
      });
      const value: unknown = await response.json();
      if (!response.ok || !isSourceProviders(value))
        throw new Error('Não foi possível verificar as capacidades das fontes. Tente novamente.');
      this.providers.set(value);
    } catch (failure) {
      this.providers.set(null);
      this.error.set(failure instanceof Error ? failure.message : 'Falha ao consultar fontes.');
    } finally {
      this.loading.set(false);
    }
  }
}
