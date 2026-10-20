import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';

type Identifier = { namespace: string; value: string };
interface Provenance {
  schemaVersion: 1;
  work: { id: string; title: string; composer: string; externalIdentifiers: Identifier[] };
  edition?: { id: string; workId: string; title: string; externalIdentifiers: Identifier[] } | null;
  arrangement: {
    id: string;
    workId: string;
    parts: { id: string; name: string; instrumentId: string }[];
  };
  sourceAsset: {
    id: string;
    filename: string;
    sha256: string;
    format: string;
    byteSize: number;
    retained: boolean;
    externalIdentifiers: Identifier[];
  };
  rights: {
    status: 'NOT_REVIEWED';
    declaredLicense?: string | null;
    licenseUrl?: string | null;
    derivation: 'UNKNOWN';
    redistribution: 'UNKNOWN';
  };
}
export interface ProjectDetails {
  provenance?: Provenance | null;
  xmlSha256: string;
}
const object = (v: unknown): v is Record<string, unknown> =>
  !!v && typeof v === 'object' && !Array.isArray(v);
const text = (v: unknown, max: number): v is string => typeof v === 'string' && v.length <= max;
const uuid = (v: unknown): v is string =>
  typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
const hash = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
const identifiers = (v: unknown): boolean =>
  Array.isArray(v) &&
  v.length <= 16 &&
  v.every((id) => object(id) && text(id['namespace'], 100) && text(id['value'], 1000));
export function isProjectDetails(value: unknown): value is ProjectDetails {
  if (!object(value) || !hash(value['xmlSha256'])) return false;
  const p = value['provenance'];
  if (p == null) return true;
  if (!object(p) || p['schemaVersion'] !== 1) return false;
  const w = p['work'],
    a = p['arrangement'],
    s = p['sourceAsset'],
    r = p['rights'],
    e = p['edition'];
  return (
    object(w) &&
    uuid(w['id']) &&
    text(w['title'], 500) &&
    text(w['composer'], 500) &&
    identifiers(w['externalIdentifiers']) &&
    object(a) &&
    uuid(a['id']) &&
    a['workId'] === w['id'] &&
    Array.isArray(a['parts']) &&
    a['parts'].length > 0 &&
    a['parts'].length <= 16 &&
    a['parts'].every(
      (part) =>
        object(part) &&
        text(part['id'], 200) &&
        text(part['name'], 500) &&
        text(part['instrumentId'], 100),
    ) &&
    (e == null ||
      (object(e) &&
        uuid(e['id']) &&
        e['workId'] === w['id'] &&
        text(e['title'], 500) &&
        identifiers(e['externalIdentifiers']))) &&
    object(s) &&
    hash(s['sha256']) &&
    s['id'] === 'sha256:' + s['sha256'] &&
    text(s['filename'], 200) &&
    !/[/\\:\p{Cc}\p{Cf}]/u.test(s['filename']) &&
    text(s['format'], 40) &&
    typeof s['retained'] === 'boolean' &&
    Number.isInteger(s['byteSize']) &&
    Number(s['byteSize']) >= 0 &&
    Number(s['byteSize']) <= 128 * 1024 * 1024 &&
    (s['retained']
      ? Number(s['byteSize']) > 0 && ['MUSICXML', 'MXL', 'MSCZ'].includes(s['format'])
      : s['byteSize'] === 0) &&
    identifiers(s['externalIdentifiers']) &&
    object(r) &&
    r['status'] === 'NOT_REVIEWED' &&
    r['derivation'] === 'UNKNOWN' &&
    r['redistribution'] === 'UNKNOWN' &&
    (r['declaredLicense'] == null || text(r['declaredLicense'], 1000)) &&
    (r['licenseUrl'] == null || text(r['licenseUrl'], 2000))
  );
}

@Component({
  selector: 'app-project-provenance',
  templateUrl: './project-provenance.html',
  styleUrl: './project-provenance.scss',
})
export class ProjectProvenancePanel {
  readonly projectId = input.required<string>();
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly details = signal<ProjectDetails | null>(null);
  readonly provenance = computed(() => this.details()?.provenance);
  readonly loading = signal(false);
  readonly downloading = signal(false);
  readonly error = signal('');
  private readonly retry = signal(0);
  private controller?: AbortController;

  constructor() {
    effect((cleanup) => {
      const id = this.projectId();
      this.retry();
      const controller = new AbortController();
      this.controller = controller;
      cleanup(() => controller.abort());
      this.details.set(null);
      this.error.set('');
      this.loading.set(false);
      this.downloading.set(false);
      if (uuid(id)) void this.load(id, controller.signal);
      else this.error.set('Identificador de projeto inválido.');
    });
  }
  reload(): void {
    this.retry.update((value) => value + 1);
  }
  private async load(id: string, signal: AbortSignal): Promise<void> {
    this.loading.set(true);
    try {
      const response = await this.request(`/api/projects/${id}/provenance`, signal);
      const data: unknown = await response.json();
      if (!response.ok || !isProjectDetails(data))
        throw new Error(
          'Não foi possível verificar a origem salva. Seus arquivos foram preservados.',
        );
      if (!signal.aborted) this.details.set(data);
    } catch (failure) {
      if (!signal.aborted)
        this.error.set(failure instanceof Error ? failure.message : 'Falha ao consultar a origem.');
    } finally {
      if (!signal.aborted) this.loading.set(false);
    }
  }
  async download(): Promise<void> {
    const asset = this.provenance()?.sourceAsset;
    const signal = this.controller?.signal;
    if (!asset?.retained || !signal || signal.aborted || this.downloading()) return;
    this.downloading.set(true);
    this.error.set('');
    try {
      const response = await this.request(`/api/projects/${this.projectId()}/original`, signal);
      if (!response.ok) throw new Error('Não foi possível recuperar o original preservado.');
      const blob = await response.blob();
      if (blob.size !== asset.byteSize)
        throw new Error('O original recebido tem tamanho divergente.');
      const digest = Array.from(
        new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())),
      )
        .map((byte) => byte.toString(16).padStart(2, '0'))
        .join('');
      if (digest !== asset.sha256)
        throw new Error('A integridade do original recebido não foi confirmada.');
      if (signal.aborted) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = asset.filename;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch (failure) {
      if (!signal.aborted)
        this.error.set(failure instanceof Error ? failure.message : 'Falha ao baixar o original.');
    } finally {
      if (!signal.aborted) this.downloading.set(false);
    }
  }
  private request(path: string, signal: AbortSignal): Promise<Response> {
    if (!this.token) return Promise.reject(new Error('Abra esta janela pelo aplicativo ARPEON.'));
    return this.fetch(path, {
      headers: { 'X-Arpeon-Session': this.token },
      credentials: 'omit',
      cache: 'no-store',
      signal: AbortSignal.any([signal, AbortSignal.timeout(60000)]),
    });
  }
}
