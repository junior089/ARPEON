import { Component, inject, input, OnDestroy, OnInit, output, signal } from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { SourceCapabilities } from '../../core/source-capabilities';

export interface CatalogueEntry {
  id: string;
  title: string;
  composer: string;
  instrument: string;
  pageUrl: string;
  sourceRevision: string;
  sourceUrl: string;
  license: string;
  licenseUrl: string;
  credit: string;
  filename: string;
  sha256: string;
  byteSize: number;
}
export function isMutopiaCatalogue(value: unknown): value is CatalogueEntry[] {
  const text = (v: unknown, max: number): v is string =>
    typeof v === 'string' && !!v.trim() && v.length <= max && !/[\p{Cc}\p{Cf}]/u.test(v);
  if (!Array.isArray(value) || !value.length || value.length > 32) return false;
  const entries = value as unknown[];
  const ids = new Set<string>();
  return entries.every((v: unknown) => {
    if (!v || typeof v !== 'object' || Array.isArray(v)) return false;
    const e = v as Record<string, unknown>;
    if (!text(e['id'], 100) || ids.has(e['id'])) return false;
    ids.add(e['id']);
    return (
      /^[a-z0-9-]+$/.test(e['id']) &&
      text(e['title'], 500) &&
      text(e['composer'], 500) &&
      text(e['instrument'], 100) &&
      text(e['credit'], 500) &&
      text(e['license'], 100) &&
      typeof e['pageUrl'] === 'string' &&
      /^https:\/\/www\.mutopiaproject\.org\/cgibin\/piece-info\.cgi\?id=[1-9][0-9]{0,7}$/.test(
        e['pageUrl'],
      ) &&
      typeof e['sourceRevision'] === 'string' &&
      /^[a-f0-9]{40}$/.test(e['sourceRevision']) &&
      typeof e['sourceUrl'] === 'string' &&
      e['sourceUrl'].startsWith(
        'https://github.com/MutopiaProject/MutopiaProject/blob/' + e['sourceRevision'] + '/ftp/',
      ) &&
      e['sourceUrl'].length <= 1000 &&
      !/[\p{Cc}\p{Cf}?#]/u.test(e['sourceUrl']) &&
      e['licenseUrl'] === 'https://creativecommons.org/licenses/publicdomain/' &&
      typeof e['filename'] === 'string' &&
      /^[a-zA-Z0-9_-]+\.mid$/.test(e['filename']) &&
      typeof e['sha256'] === 'string' &&
      /^[a-f0-9]{64}$/.test(e['sha256']) &&
      Number.isSafeInteger(e['byteSize']) &&
      Number(e['byteSize']) > 0 &&
      Number(e['byteSize']) <= 1048576
    );
  });
}
@Component({
  selector: 'app-mutopia-catalogue',
  templateUrl: './mutopia-catalogue.html',
  styleUrl: './mutopia-catalogue.scss',
})
export class MutopiaCatalogue implements OnInit, OnDestroy {
  readonly busy = input(false);
  readonly selected = output<CatalogueEntry>();
  readonly entries = signal<CatalogueEntry[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  private readonly sources = inject(SourceCapabilities);
  private readonly controller = new AbortController();
  ngOnInit(): void {
    void this.load();
  }
  async load(): Promise<void> {
    if (this.loading() || this.controller.signal.aborted) return;
    this.loading.set(true);
    this.error.set('');
    try {
      await this.sources.load();
      if (!this.token || !this.sources.canImportMutopia())
        throw new Error(
          this.sources.error() || 'O catálogo Mutopia não está disponível nesta sessão.',
        );
      if (this.controller.signal.aborted) return;
      const response = await this.fetch('/api/import/catalogue/mutopia', {
        headers: { 'X-Arpeon-Session': this.token },
        credentials: 'omit',
        cache: 'no-store',
        signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout(10000)]),
      });
      const data: unknown = await response.json();
      if (!response.ok || !isMutopiaCatalogue(data))
        throw new Error('Não foi possível consultar a seleção revisada. Tente novamente.');
      if (!this.controller.signal.aborted) this.entries.set(data);
    } catch (failure) {
      if (!this.controller.signal.aborted) {
        this.entries.set([]);
        this.error.set(failure instanceof Error ? failure.message : 'Falha ao consultar Mutopia.');
      }
    } finally {
      if (!this.controller.signal.aborted) this.loading.set(false);
    }
  }
  ngOnDestroy(): void {
    this.controller.abort();
  }
}
